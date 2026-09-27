package com.tether.tools.designtokens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.math.BigDecimal
import kotlin.system.exitProcess

/**
 * PLAN D9 / T3.1: turns the tether token export (`parity-corpus/tokens/design-tokens.json`,
 * written by tether `scripts/export-design-tokens.mjs`) into
 * `core/designsystem/.../ui/theme/GeneratedTokens.kt`.
 *
 * Every token gets ONE Kotlin type for all skins, the first [Kind] (in declaration order) that
 * parses every skin's resolved value. Output is deterministic: skins in skinMap order
 * (family × light/dark), tokens sorted by CSS name, fixed number formatting, "\n" line endings.
 */
enum class Kind(val kotlinType: String, val mapping: String) {
    COLOR("Color", "#hex / rgb() / rgba() / transparent -> Color (sRGB ARGB; alpha quantized like Compose's Color(Float...))"),
    RGB_TRIPLE("Color", "bare `r g b` channel triple (read as rgb(var(--x) / a) in CSS) -> opaque Color"),
    LENGTH("Dp", "px / rem length (bare 0 allowed) -> Dp; 1px = 1dp, 1rem = 16dp"),
    EM("TextUnit", "em length (bare 0 allowed) -> TextUnit(Em)"),
    PERCENT("Float", "percentage -> Float fraction (86% -> 0.86)"),
    DURATION("Int", "ms / s duration -> Int milliseconds"),
    INT("Int", "unitless integer -> Int"),
    NUMBER("Float", "unitless number -> Float"),
    EASING("CssCubicBezier", "cubic-bezier() -> CssCubicBezier (toEasing() gives the Compose Easing)"),
    SHADOW("List<CssShadow>", "box-shadow list -> List<CssShadow> (outer + inset layers; none -> empty)"),
    STRING("String", "no Compose equivalent (gradients, keywords, font stacks) -> resolved CSS text"),
}

/** One box-shadow layer, lengths in dp. */
data class ShadowLayer(
    val inset: Boolean,
    val x: BigDecimal,
    val y: BigDecimal,
    val blur: BigDecimal,
    val spread: BigDecimal,
    val argb: Long,
)

/** Minimal CSS value parsing for the shapes the exporter emits (resolved, var()-free). */
object Css {
    private val NUM = Regex("""-?(?:\d+\.?\d*|\.\d+)""")
    private val HEX = Regex("""#([0-9a-fA-F]{3,8})""")
    private val FUNC = Regex("""(rgba?)\((.*)\)""")
    private val LEN = Regex("""(-?(?:\d+\.?\d*|\.\d+))(px|rem)?""")
    private val EM = Regex("""(-?(?:\d+\.?\d*|\.\d+))(em)?""")
    private val BEZIER = Regex("""cubic-bezier\(\s*([^,]+),\s*([^,]+),\s*([^,]+),\s*([^,)]+)\)""")
    private val SIXTEEN = BigDecimal(16)

    /** Compose's sRGB quantization: (component * 255f + 0.5f).toInt(). */
    private fun q(unit: Float): Long = (unit.coerceIn(0f, 1f) * 255.0f + 0.5f).toInt().toLong()

    private fun argb(r: Float, g: Float, b: Float, a: Float): Long =
        (q(a) shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)

    fun color(s: String): Long? {
        val v = s.trim()
        if (v == "transparent") return 0L
        HEX.matchEntire(v)?.let { m ->
            val h = m.groupValues[1]
            val full = when (h.length) {
                3, 4 -> h.map { "$it$it" }.joinToString("")
                6, 8 -> h
                else -> return null
            }
            val rgb = full.substring(0, 6).toLong(16)
            val a = if (full.length == 8) full.substring(6, 8).toLong(16) else 0xFFL
            return (a shl 24) or rgb
        }
        val m = FUNC.matchEntire(v) ?: return null
        val inner = m.groupValues[2].trim()
        val (channels, alpha) = when {
            '/' in inner -> inner.substringBefore('/').trim().split(Regex("\\s+")) to inner.substringAfter('/').trim()
            ',' in inner -> inner.split(',').map { it.trim() }.let { it.take(3) to it.getOrNull(3) }
            else -> inner.split(Regex("\\s+")) to null
        }
        if (channels.size != 3) return null
        val rgb = channels.map { channel(it) ?: return null }
        val a = if (alpha == null) 1f else alphaValue(alpha) ?: return null
        return argb(rgb[0], rgb[1], rgb[2], a)
    }

    private fun channel(s: String): Float? =
        if (s.endsWith('%')) s.dropLast(1).toFloatOrNull()?.div(100f)
        else s.toFloatOrNull()?.div(255f)

    private fun alphaValue(s: String): Float? =
        if (s.endsWith('%')) s.dropLast(1).toFloatOrNull()?.div(100f) else s.toFloatOrNull()

    fun rgbTriple(s: String): Long? {
        val parts = s.trim().split(Regex("\\s+"))
        if (parts.size != 3 || parts.any { !NUM.matches(it) }) return null
        val c = parts.map { it.toFloat() / 255f }
        return argb(c[0], c[1], c[2], 1f)
    }

    /** px/rem -> dp; a bare number is only a length when it is zero. */
    fun length(s: String): Pair<BigDecimal, Boolean>? {
        val m = LEN.matchEntire(s.trim()) ?: return null
        val n = BigDecimal(m.groupValues[1])
        return when (m.groupValues[2]) {
            "px" -> n to true
            "rem" -> n.multiply(SIXTEEN) to true
            else -> if (n.signum() == 0) BigDecimal.ZERO to false else null
        }
    }

    fun em(s: String): Pair<BigDecimal, Boolean>? {
        val m = EM.matchEntire(s.trim()) ?: return null
        val n = BigDecimal(m.groupValues[1])
        return if (m.groupValues[2] == "em") n to true else if (n.signum() == 0) BigDecimal.ZERO to false else null
    }

    fun percent(s: String): BigDecimal? {
        val v = s.trim()
        if (!v.endsWith('%') || !NUM.matches(v.dropLast(1))) return null
        return BigDecimal(v.dropLast(1)).movePointLeft(2)
    }

    fun durationMs(s: String): Int? {
        val v = s.trim()
        return when {
            v.endsWith("ms") && NUM.matches(v.dropLast(2)) -> BigDecimal(v.dropLast(2)).intValueExact()
            v.endsWith("s") && NUM.matches(v.dropLast(1)) -> BigDecimal(v.dropLast(1)).movePointRight(3).intValueExact()
            else -> null
        }
    }

    fun int(s: String): Int? = s.trim().takeIf { Regex("-?\\d+").matches(it) }?.toInt()

    fun number(s: String): BigDecimal? = s.trim().takeIf { NUM.matches(it) }?.let(::BigDecimal)

    fun bezier(s: String): List<BigDecimal>? {
        val m = BEZIER.matchEntire(s.trim()) ?: return null
        return m.groupValues.drop(1).map { number(it) ?: return null }
    }

    /** Splits on [sep] outside parentheses. */
    fun splitTop(s: String, sep: (Char) -> Boolean): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in s) {
            when {
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { depth--; cur.append(c) }
                depth == 0 && sep(c) -> { if (cur.isNotBlank()) out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    fun shadow(s: String): List<ShadowLayer>? {
        val v = s.trim()
        if (v == "none") return emptyList()
        return splitTop(v) { it == ',' }.map { layer ->
            val parts = splitTop(layer) { it.isWhitespace() }.toMutableList()
            val inset = parts.remove("inset")
            val lengths = mutableListOf<BigDecimal>()
            var color: Long? = null
            for (p in parts) {
                val len = length(p)
                if (len != null) {
                    lengths += len.first
                } else {
                    if (color != null) return null
                    color = color(p) ?: return null
                }
            }
            if (color == null || lengths.size !in 2..4) return null
            ShadowLayer(
                inset = inset,
                x = lengths[0],
                y = lengths[1],
                blur = lengths.getOrElse(2) { BigDecimal.ZERO },
                spread = lengths.getOrElse(3) { BigDecimal.ZERO },
                argb = color,
            )
        }
    }

    /** "(min-width: 48rem) and (max-width: 99.999rem)" -> (min dp, max dp). */
    fun mediaWidths(at: String): Pair<BigDecimal?, BigDecimal?>? {
        val body = at.trim().removePrefix("@media").trim()
        var min: BigDecimal? = null
        var max: BigDecimal? = null
        for (clause in body.split(" and ")) {
            val m = Regex("""\(\s*(min|max)-width:\s*([^)]+)\)""").matchEntire(clause.trim()) ?: return null
            val len = length(m.groupValues[2])?.first ?: return null
            if (m.groupValues[1] == "min") min = len else max = len
        }
        return min to max
    }
}

class TokenSpecError(message: String) : IllegalStateException(message)

private fun fail(msg: String): Nothing = throw TokenSpecError(msg)

/** Picks the single Kotlin kind that parses every skin's value for one token. */
fun classify(values: List<String>): Kind = Kind.entries.first { kind ->
    when (kind) {
        Kind.COLOR -> values.all { Css.color(it) != null }
        Kind.RGB_TRIPLE -> values.all { Css.rgbTriple(it) != null }
        Kind.LENGTH -> values.map { Css.length(it) }.let { l -> l.all { it != null } && l.any { it!!.second } }
        Kind.EM -> values.map { Css.em(it) }.let { l -> l.all { it != null } && l.any { it!!.second } }
        Kind.PERCENT -> values.all { Css.percent(it) != null }
        Kind.DURATION -> values.all { Css.durationMs(it) != null }
        Kind.INT -> values.all { Css.int(it) != null }
        Kind.NUMBER -> values.all { Css.number(it) != null }
        Kind.EASING -> values.all { Css.bezier(it) != null }
        Kind.SHADOW -> values.all { Css.shadow(it) != null }
        Kind.STRING -> true
    }
}

private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)

/** `--accent-deep` -> `accentDeep`, `--space-2xl` -> `space2xl`. */
fun propertyName(css: String): String {
    val parts = css.removePrefix("--").split('-').filter { it.isNotEmpty() }
    val name = parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    return if (name in KOTLIN_KEYWORDS) "`$name`" else name
}

/** `studio-dark` -> `StudioDark`. */
fun constantName(id: String): String =
    id.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

private fun plain(n: BigDecimal): String = n.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
private fun colorLit(argb: Long) = "Color(0x%08X)".format(argb)
private fun dpLit(n: BigDecimal) = "Dp(${plain(n)}f)"
private fun floatLit(n: BigDecimal) = "${plain(n)}f"

fun kotlinString(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '$' -> append("\\$")
        '\n' -> append("\\n")
        else -> append(c)
    }
    append('"')
}

private fun literal(kind: Kind, v: String, indent: String): String = when (kind) {
    Kind.COLOR -> colorLit(Css.color(v)!!)
    Kind.RGB_TRIPLE -> colorLit(Css.rgbTriple(v)!!)
    Kind.LENGTH -> dpLit(Css.length(v)!!.first)
    Kind.EM -> "TextUnit(${floatLit(Css.em(v)!!.first)}, TextUnitType.Em)"
    Kind.PERCENT -> floatLit(Css.percent(v)!!)
    Kind.DURATION -> Css.durationMs(v)!!.toString()
    Kind.INT -> Css.int(v)!!.toString()
    Kind.NUMBER -> floatLit(Css.number(v)!!)
    Kind.EASING -> "CssCubicBezier(${Css.bezier(v)!!.joinToString(", ") { floatLit(it) }})"
    Kind.SHADOW -> Css.shadow(v)!!.let { layers ->
        if (layers.isEmpty()) {
            "emptyList()"
        } else {
            layers.joinToString(",\n", prefix = "listOf(\n", postfix = ",\n$indent)") { l ->
                "$indent    CssShadow(inset = ${l.inset}, offsetX = ${dpLit(l.x)}, offsetY = ${dpLit(l.y)}, " +
                    "blur = ${dpLit(l.blur)}, spread = ${dpLit(l.spread)}, color = ${colorLit(l.argb)})"
            }
        }
    }
    Kind.STRING -> kotlinString(v)
}

private fun JsonElement.str(): String = jsonPrimitive.content
private fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject ?: fail("missing object '$key'")

/** Reads the token JSON and returns the full GeneratedTokens.kt text. */
fun generate(jsonText: String): String {
    val root = Json.parseToJsonElement(jsonText).jsonObject
    val tetherSha = root["tetherSha"]?.str() ?: fail("missing tetherSha")
    val protocolVersion = root["protocolVersion"]?.str() ?: fail("missing protocolVersion")
    val generatedBy = root["generatedBy"]?.str() ?: fail("missing generatedBy")
    val sources = root["sources"]?.jsonArray?.map { it.str() } ?: fail("missing sources")
    val families = root["families"]?.jsonArray?.map { it.str() } ?: fail("missing families")
    val skinMap = root.obj("skinMap")
    val skinsJson = root.obj("skins")

    // Skin order: family order x (light, dark), straight from the skinMap.
    data class SkinRef(val id: String, val family: String, val dark: Boolean)
    val skinRefs = families.flatMap { f ->
        val pair = skinMap.obj(f)
        listOf(SkinRef(pair["light"]?.str() ?: fail("skinMap.$f.light"), f, false), SkinRef(pair["dark"]?.str() ?: fail("skinMap.$f.dark"), f, true))
    }
    if (skinRefs.map { it.id }.toSet() != skinsJson.keys) fail("skinMap skins ${skinRefs.map { it.id }} != skins ${skinsJson.keys.sorted()}")

    class Skin(val ref: SkinRef, val tokens: Map<String, String>, val isDark: Boolean, val barColor: Long)
    val skins = skinRefs.map { ref ->
        val s = skinsJson.obj(ref.id)
        if (s["family"]?.str() != ref.family) fail("skin ${ref.id}: family ${s["family"]} != skinMap ${ref.family}")
        val mode = if (ref.dark) "dark" else "light"
        if (s["mode"]?.str() != mode) fail("skin ${ref.id}: mode ${s["mode"]} != skinMap $mode")
        val scheme = s.obj("properties").obj("color-scheme")["resolved"]?.str()
        val isDark = when (scheme) {
            "dark" -> true
            "light" -> false
            else -> fail("skin ${ref.id}: color-scheme '$scheme' is not light|dark")
        }
        val chrome = s.obj("chrome")
        val graphite = chrome["graphite"]?.str() ?: fail("skin ${ref.id}: missing chrome.graphite")
        val boot = chrome["bootThemeColor"]?.str()
        if (boot != null && boot != graphite) fail("skin ${ref.id}: chrome.bootThemeColor $boot != chrome.graphite $graphite")
        val tokens = s.obj("tokens").mapValues { (_, v) -> v.jsonObject["resolved"]?.str() ?: fail("unresolved token") }
        Skin(ref, tokens, isDark, Css.color(graphite) ?: fail("skin ${ref.id}: bad chrome.graphite $graphite"))
    }

    // Responsive (@media) tokens: the exporter repeats them per skin; they must agree.
    val conditional = skinsJson.values.map { it.jsonObject["conditional"]?.jsonArray ?: fail("missing conditional") }
    if (conditional.distinct().size != 1) fail("conditional (@media) tokens differ between skins")
    val media = conditional.first().map { e ->
        val o = e.jsonObject
        val at = o["at"]?.str() ?: fail("conditional.at")
        val widths = Css.mediaWidths(at) ?: fail("unsupported media query '$at'")
        val value = Css.length(o["raw"]?.str() ?: fail("conditional.raw"))?.first ?: fail("conditional value is not a length")
        Triple(o["name"]?.str() ?: fail("conditional.name"), at, widths to value)
    }

    val names = skins.flatMap { it.tokens.keys }.toSortedSet()
    class Token(val css: String, val prop: String, val kind: Kind, val optional: Boolean)
    val tokens = names.map { css ->
        val present = skins.mapNotNull { it.tokens[css] }
        Token(css, propertyName(css), classify(present), present.size < skins.size)
    }
    tokens.groupBy { it.prop }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { fail("property name collision: $it") }

    val sb = StringBuilder()
    fun line(s: String = "") = sb.append(s).append('\n')

    line("// GENERATED FILE — DO NOT EDIT. PLAN D9 / T3.1.")
    line("// Source: parity-corpus/tokens/design-tokens.json — tether $tetherSha ($generatedBy),")
    line("// protocol v$protocolVersion, from ${sources.joinToString(" + ")}.")
    line("// Generator: tools/design-tokens. Regenerate: ./gradlew generateDesignTokens")
    line("// Drift check: ./gradlew verifyDesignTokens (wired into :core:designsystem:check).")
    line("// ${skins.size} skins, ${tokens.size} tokens. Category mapping:")
    Kind.entries.forEach { k -> line("//   ${k.name.lowercase().padEnd(10)} ${k.mapping}") }
    line()
    line("package com.tether.app.ui.theme")
    line()
    line("import androidx.compose.runtime.Immutable")
    line("import androidx.compose.ui.graphics.Color")
    line("import androidx.compose.ui.unit.Dp")
    line("import androidx.compose.ui.unit.TextUnit")
    line("import androidx.compose.ui.unit.TextUnitType")
    line()
    line("/** Provenance of the token corpus this file was generated from. */")
    line("object DesignTokenSource {")
    line("    const val TETHER_SHA: String = ${kotlinString(tetherSha)}")
    line("    const val PROTOCOL_VERSION: Int = ${protocolVersion.toInt()}")
    line("    const val EXPORTER: String = ${kotlinString(generatedBy)}")
    line("    val SOURCES: List<String> = listOf(${sources.joinToString(", ") { kotlinString(it) }})")
    line("}")
    line()
    line("/**")
    line(" * The six web skins (`<html data-theme>`), in skinMap order. [isDark] is the skin's CSS")
    line(" * `color-scheme`; [systemBarColor] is its `chrome.graphite` (the web's theme-color / boot-script")
    line(" * COLORS entry), used for the status and navigation bars.")
    line(" */")
    line("enum class TetherSkin(val id: String, val family: ThemeFamily, val isDark: Boolean, val systemBarColor: Color) {")
    skins.forEach { s ->
        line("    ${constantName(s.ref.id)}(${kotlinString(s.ref.id)}, ThemeFamily.${constantName(s.ref.family)}, isDark = ${s.isDark}, systemBarColor = ${colorLit(s.barColor)}),")
    }
    line("    ;")
    line()
    line("    /** This skin's complete token set. */")
    line("    val tokens: SkinTokens")
    line("        get() = when (this) {")
    skins.forEach { s -> line("            ${constantName(s.ref.id)} -> GeneratedTokens.${constantName(s.ref.id)}") }
    line("        }")
    line()
    line("    companion object {")
    line("        fun fromId(id: String?): TetherSkin? = entries.firstOrNull { it.id == id }")
    line()
    line("        /** The web's THEME_SKINS: family × concrete lighting → skin. */")
    line("        fun of(family: ThemeFamily, dark: Boolean): TetherSkin = when (family) {")
    families.forEach { f ->
        val light = skinRefs.first { it.family == f && !it.dark }.id
        val dark = skinRefs.first { it.family == f && it.dark }.id
        line("            ThemeFamily.${constantName(f)} -> if (dark) ${constantName(dark)} else ${constantName(light)}")
    }
    line("        }")
    line("    }")
    line("}")
    line()
    line("/** One skin's full CSS custom-property set, typed. Property KDoc names the CSS variable. */")
    line("@Immutable")
    line("data class SkinTokens(")
    tokens.forEach { t ->
        line("    /** `${t.css}` (${t.kind.name.lowercase()}${if (t.optional) ", absent in some skins" else ""}) */")
        line("    val ${t.prop}: ${t.kind.kotlinType}${if (t.optional) "?" else ""},")
    }
    line(") {")
    line("    /** Every token keyed by its CSS name (tests compare this against the JSON). */")
    line("    fun byCssName(): Map<String, Any?> = linkedMapOf(")
    tokens.forEach { t -> line("        ${kotlinString(t.css)} to ${t.prop},") }
    line("    )")
    line("}")
    line()
    line("object GeneratedTokens {")
    skins.forEach { s -> line("    val ${constantName(s.ref.id)}: SkinTokens = ${propertyName("--" + s.ref.id)}Tokens()") }
    line()
    line("    /** `@media` tokens (the same in every skin), in source order. */")
    line("    val responsive: List<CssMediaToken> = listOf(")
    media.forEach { (name, at, wv) ->
        val (widths, value) = wv
        line("        CssMediaToken(${kotlinString(name)}, ${kotlinString(at)}, ${widths.first?.let(::dpLit) ?: "null"}, ${widths.second?.let(::dpLit) ?: "null"}, ${dpLit(value)}),")
    }
    line("    )")
    line("}")
    skins.forEach { s ->
        line()
        line("private fun ${propertyName("--" + s.ref.id)}Tokens(): SkinTokens = SkinTokens(")
        tokens.forEach { t ->
            val v = s.tokens[t.css]
            line("    ${t.prop} = ${if (v == null) "null" else literal(t.kind, v, "    ")},")
        }
        line(")")
    }
    return sb.toString()
}

/**
 * Regenerates [json] into [temp] and compares it with the checked-in [generated] file.
 * Returns null when identical, otherwise a human-readable drift report.
 */
fun verify(json: File, generated: File, temp: File): String? {
    val fresh = generate(json.readText())
    temp.parentFile?.mkdirs()
    temp.writeText(fresh)
    val current = if (generated.exists()) generated.readText() else ""
    if (current == fresh) return null
    val a = current.lines()
    val b = fresh.lines()
    val i = (0 until maxOf(a.size, b.size)).first { a.getOrNull(it) != b.getOrNull(it) }
    return buildString {
        append("Design tokens drifted: ${generated.path} does not match ${json.path}.\n")
        append("First difference at line ${i + 1}:\n")
        append("  checked in: ${a.getOrNull(i) ?: "<end of file>"}\n")
        append("  expected  : ${b.getOrNull(i) ?: "<end of file>"}\n")
        append("Regenerated copy: ${temp.path}\n")
        append("Fix: ./gradlew generateDesignTokens (and commit the result).")
    }
}

/** `generate <json> <out>` | `verify <json> <generated> <temp>` */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "generate" -> {
            require(args.size == 3) { "usage: generate <json> <out>" }
            val out = File(args[2])
            out.parentFile?.mkdirs()
            out.writeText(generate(File(args[1]).readText()))
            println("wrote ${out.path}")
        }
        "verify" -> {
            require(args.size == 4) { "usage: verify <json> <generated> <temp>" }
            val drift = verify(File(args[1]), File(args[2]), File(args[3]))
            if (drift != null) {
                System.err.println(drift)
                exitProcess(1)
            }
            println("design tokens up to date: ${args[2]}")
        }
        else -> {
            System.err.println("usage: generate <json> <out> | verify <json> <generated> <temp>")
            exitProcess(2)
        }
    }
}
