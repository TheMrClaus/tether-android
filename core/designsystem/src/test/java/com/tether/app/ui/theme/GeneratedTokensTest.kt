package com.tether.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every generated token of both Studio skins equals the JSON value. The CSS values are parsed
 * here independently of the generator (tools/design-tokens) so a generator bug cannot hide.
 */
class GeneratedTokensTest {

    private fun resolved(skin: String): Map<String, String> =
        TokenCorpus.skins.getValue(skin).jsonObject.getValue("tokens").jsonObject
            .mapValues { it.value.jsonObject.getValue("resolved").jsonPrimitive.content }

    // ── independent CSS parsing ──────────────────────────────────────────────

    private fun num(s: String): Float = s.trim().toFloat()

    /** CSS colour → the Color Compose builds from the same float components. */
    private fun cssColor(s: String): Color? {
        val v = s.trim()
        if (v == "transparent") return Color(0f, 0f, 0f, 0f)
        if (v.startsWith("#")) {
            var h = v.drop(1)
            if (h.length == 3 || h.length == 4) h = h.map { "$it$it" }.joinToString("")
            if (h.length != 6 && h.length != 8) return null
            val c = h.chunked(2).map { it.toInt(16) }
            return Color(c[0], c[1], c[2], c.getOrElse(3) { 255 })
        }
        val m = Regex("""rgba?\(([^)]*)\)""").matchEntire(v)
        if (m != null) {
            val inner = m.groupValues[1]
            val parts = inner.replace("/", " ").replace(",", " ").trim().split(Regex("\\s+"))
            if (parts.size !in 3..4) return null
            val a = parts.getOrNull(3)?.let { if (it.endsWith("%")) num(it.dropLast(1)) / 100f else num(it) } ?: 1f
            return Color(num(parts[0]) / 255f, num(parts[1]) / 255f, num(parts[2]) / 255f, a)
        }
        // A bare "r g b" channel triple (--contact / --tint-rgb).
        val triple = v.split(Regex("\\s+"))
        if (triple.size == 3 && triple.all { it.toFloatOrNull() != null }) {
            return Color(num(triple[0]) / 255f, num(triple[1]) / 255f, num(triple[2]) / 255f, 1f)
        }
        return null
    }

    private fun cssLengthDp(s: String): Float? {
        val v = s.trim()
        return when {
            v.endsWith("rem") -> v.dropLast(3).toFloatOrNull()?.times(16f)
            v.endsWith("px") -> v.dropLast(2).toFloatOrNull()
            v == "0" -> 0f
            else -> null
        }
    }

    private fun splitOutsideParens(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        s.forEachIndexed { i, c ->
            if (c == '(') depth++
            if (c == ')') depth--
            if (depth == 0 && c == sep) {
                out += s.substring(start, i)
                start = i + 1
            }
        }
        out += s.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun assertShadow(where: String, css: String, actual: List<*>) {
        if (css.trim() == "none") {
            assertTrue("$where: none -> empty", actual.isEmpty())
            return
        }
        val layers = splitOutsideParens(css, ',')
        assertEquals("$where: layer count", layers.size, actual.size)
        layers.zip(actual).forEachIndexed { i, (layer, a) ->
            val shadow = a as CssShadow
            val parts = splitOutsideParens(layer, ' ')
            val inset = "inset" in parts
            val lengths = parts.filter { it != "inset" }.mapNotNull(::cssLengthDp)
            val color = parts.filter { it != "inset" && cssLengthDp(it) == null }.single()
            assertEquals("$where[$i] inset", inset, shadow.inset)
            assertEquals("$where[$i] x", lengths[0], shadow.offsetX.value, 1e-4f)
            assertEquals("$where[$i] y", lengths[1], shadow.offsetY.value, 1e-4f)
            assertEquals("$where[$i] blur", lengths.getOrElse(2) { 0f }, shadow.blur.value, 1e-4f)
            assertEquals("$where[$i] spread", lengths.getOrElse(3) { 0f }, shadow.spread.value, 1e-4f)
            assertEquals("$where[$i] color", cssColor(color), shadow.color)
        }
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun everyGeneratedTokenEqualsTheJsonValueInBothStudioSkins() {
        val seen = mutableMapOf<String, Int>()
        assertEquals(2, TokenCorpus.skins.size)
        for (id in TokenCorpus.skins.keys) {
            val skin = assertNotNullSkin(id)
            val json = resolved(id)
            val generated = skin.tokens.byCssName()
            // Same token set: generated non-null entries are exactly the skin's JSON tokens.
            assertEquals("$id token names", json.keys, generated.filterValues { it != null }.keys)
            for ((name, css) in json) {
                val where = "$id $name = '$css'"
                when (val v = generated.getValue(name)) {
                    is Color -> assertEquals(where, cssColor(css) ?: throw AssertionError("$where: not a colour"), v).also { seen.bump("color") }
                    is Dp -> assertEquals(where, cssLengthDp(css) ?: throw AssertionError("$where: not a length"), v.value, 1e-4f).also { seen.bump("length") }
                    is TextUnit -> {
                        assertEquals(where, TextUnitType.Em, v.type)
                        assertEquals(where, if (css == "0") 0f else num(css.removeSuffix("em")), v.value, 1e-6f)
                        seen.bump("em")
                    }
                    is Int -> assertEquals(where, css.removeSuffix("ms").toInt(), v).also { seen.bump("int/ms") }
                    is Float -> {
                        val expected = if (css.endsWith("%")) num(css.dropLast(1)) / 100f else num(css)
                        assertEquals(where, expected, v, 1e-6f)
                        seen.bump("float")
                    }
                    is CssCubicBezier -> {
                        val n = Regex("""cubic-bezier\((.*)\)""").matchEntire(css)!!.groupValues[1].split(',').map(::num)
                        assertEquals(where, n, listOf(v.x1, v.y1, v.x2, v.y2))
                        seen.bump("easing")
                    }
                    is List<*> -> assertShadow(where, css, v).also { seen.bump("shadow") }
                    is String -> assertEquals(where, css, v).also { seen.bump("string") }
                    else -> fail("$where: unexpected generated type ${v?.javaClass}")
                }
            }
        }
        // Every category the corpus has is exercised (2 skins × tokens in it). `em` has had no token
        // since --key-label-tracking was retired (tether 887c222); its branch above stays for the next.
        listOf("color", "length", "int/ms", "float", "easing", "shadow", "string").forEach {
            assertTrue("category $it covered: $seen", (seen[it] ?: 0) > 0)
        }
    }

    private fun MutableMap<String, Int>.bump(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    private fun assertNotNullSkin(id: String): TetherSkin =
        TetherSkin.fromId(id) ?: throw AssertionError("no TetherSkin for JSON skin '$id'")

    @Test
    fun skinMetadataMatchesJson() {
        assertEquals(TokenCorpus.root.getValue("tetherSha").jsonPrimitive.content, DesignTokenSource.TETHER_SHA)
        assertEquals(TokenCorpus.skins.keys, TetherSkin.entries.map { it.id }.toSet())
        for (skin in TetherSkin.entries) {
            val s = TokenCorpus.skins.getValue(skin.id).jsonObject
            // T15.5: Studio-only export, no theme family anywhere.
            assertEquals(skin.id, null, s["family"])
            val scheme = s.getValue("properties").jsonObject.getValue("color-scheme").jsonObject
                .getValue("resolved").jsonPrimitive.content
            assertEquals(skin.id, scheme == "dark", skin.isDark)
            assertEquals(skin.id, s.getValue("mode").jsonPrimitive.content == "dark", skin.isDark)
            val graphite = s.getValue("chrome").jsonObject.getValue("graphite").jsonPrimitive.content
            assertEquals(skin.id, cssColor(graphite), skin.systemBarColor)
            // The bar colour is the skin's panel colour (--graphite), as on the web.
            assertEquals(skin.id, skin.tokens.graphite, skin.systemBarColor)
        }
        // The mode axis: the hand-written :core:data enum carries exactly the JSON's modes.
        assertEquals(null, TokenCorpus.root["families"])
        assertEquals(
            TokenCorpus.root.getValue("modes").jsonArray.map { it.jsonPrimitive.content },
            ThemeMode.entries.map { it.id },
        )
    }

    @Test
    fun skinMapMatchesJson() {
        // Studio-only (T15.5): mode -> skin, as the web exporter now writes it.
        val map = TokenCorpus.root.getValue("skinMap").jsonObject
        assertEquals(setOf("light", "dark"), map.keys)
        assertEquals(map.getValue("light").jsonPrimitive.content, TetherSkin.of(dark = false).id)
        assertEquals(map.getValue("dark").jsonPrimitive.content, TetherSkin.of(dark = true).id)
        assertEquals(ThemeMode.Light.resolve(systemDark = true), TetherSkin.fromId(map.getValue("light").jsonPrimitive.content))
        assertEquals(ThemeMode.Dark.resolve(systemDark = false), TetherSkin.fromId(map.getValue("dark").jsonPrimitive.content))
    }

    @Test
    fun responsiveTokensMatchJsonConditional() {
        for (id in TokenCorpus.skins.keys) {
            val cond = TokenCorpus.skins.getValue(id).jsonObject.getValue("conditional").jsonArray
            assertEquals(id, cond.size, GeneratedTokens.responsive.size)
            cond.zip(GeneratedTokens.responsive).forEach { (e, t) ->
                val o = e.jsonObject
                assertEquals(o.getValue("name").jsonPrimitive.content, t.name)
                assertEquals(o.getValue("at").jsonPrimitive.content, t.media)
                assertEquals(cssLengthDp(o.getValue("raw").jsonPrimitive.content)!!, t.value.value, 1e-4f)
            }
        }
        assertEquals(768f, GeneratedTokens.responsive[0].minWidth!!.value, 1e-4f)
    }

    @Test
    fun legacyFacadeReadsTheGeneratedValues() {
        for (skin in TetherSkin.entries) {
            val t = tokensFor(skin)
            val json = resolved(skin.id)
            assertEquals(skin.id, cssColor(json.getValue("--mineral")), t.mineral)
            assertEquals(skin.id, cssColor(json.getValue("--graphite")), t.graphite)
            assertEquals(skin.id, cssColor(json.getValue("--violet-strong")), t.violetStrong)
            assertEquals(skin.id, cssColor(json.getValue("--tint-md")), t.tintMd)
            assertEquals(skin.id, cssLengthDp(json.getValue("--radius-key"))!!, t.radiusKey.value, 1e-4f)
        }
        assertNotNull(GeneratedTokens.Studio.fontUi)
    }
}
