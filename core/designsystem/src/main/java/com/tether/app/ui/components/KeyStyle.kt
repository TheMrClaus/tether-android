package com.tether.app.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.TetherTokens
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * The key system of the web's material layer, resolved the way the browser resolves it.
 *
 * A web key is a `<button>` carrying SEVERAL classes (`button-secondary chat-approval-deny`,
 * `chat-send chat-interrupt`, `button-primary button-danger`…), and every rule that matches ANY
 * of its classes takes part. So a key here is its web CLASS SET ([KeyClass]); [resolveKey] runs a
 * small cascade over [KeyRules] — the rules of globals.css and studio.css that paint keys — with
 * the browser's precedence: matching rules sorted by specificity, then source order, studio.css
 * loading after globals.css; later declarations win.
 *
 * Modelled states: rest, pressed (`:active:not(:disabled)`), disabled (`:disabled`). Keyboard
 * focus is a separate colour-free ring ([focusRing]). `:hover` is not a state of a touch key and
 * is not modelled.
 */
enum class KeyClass(val css: String) {
    ButtonPrimary("button-primary"),
    ButtonSecondary("button-secondary"),
    ButtonDanger("button-danger"),

    /** Only ever rendered inside `.chat-approval-actions`; its rules include that ancestor. */
    ChatApprovalDeny("chat-approval-deny"),
    ChatSend("chat-send"),
    ChatInterrupt("chat-interrupt"),
    EndSession("end-session"),
    NewSessionButton("new-session-button"),

    /** The composer's paperclip, inside `.chat-composer-toolbar` (Studio restyles it there). */
    ChatAttachBtn("chat-attach-btn"),
    ChatJump("chat-jump"),
    IconButton("icon-button"),
}

/** The class sets the app renders, named after the web markup they mirror. */
object KeyClasses {
    /** `<button className="button-primary">` (Approve, Save settings, Use this folder…). */
    val ButtonPrimary: Set<KeyClass> = setOf(KeyClass.ButtonPrimary)

    /** `<button className="button-secondary">` (Cancel, provider choices…). */
    val ButtonSecondary: Set<KeyClass> = setOf(KeyClass.ButtonSecondary)

    /** Confirm dialogs' destructive action: `button-primary button-danger`. */
    val ButtonDanger: Set<KeyClass> = setOf(KeyClass.ButtonPrimary, KeyClass.ButtonDanger)

    /** The explicit approval Deny: `button-secondary chat-approval-deny` (chat-view.tsx:1316). */
    val ApprovalDeny: Set<KeyClass> = setOf(KeyClass.ButtonSecondary, KeyClass.ChatApprovalDeny)

    /** Composer Send / Queue: `chat-send` (chat-view.tsx:4470, 4502). */
    val ChatSend: Set<KeyClass> = setOf(KeyClass.ChatSend)

    /** Composer Interrupt: `chat-send chat-interrupt` (chat-view.tsx:4474). */
    val ChatInterrupt: Set<KeyClass> = setOf(KeyClass.ChatSend, KeyClass.ChatInterrupt)

    /** The session header's destructive key: `end-session`. */
    val EndSession: Set<KeyClass> = setOf(KeyClass.EndSession)

    /** The sidebar's New session key: `new-session-button`. */
    val NewSession: Set<KeyClass> = setOf(KeyClass.NewSessionButton)

    /** The composer paperclip: `chat-attach-btn`. */
    val Attach: Set<KeyClass> = setOf(KeyClass.ChatAttachBtn)

    /** Jump-to-latest: `chat-jump`. */
    val ChatJump: Set<KeyClass> = setOf(KeyClass.ChatJump)

    /** Icon controls: `icon-button`. */
    val IconButton: Set<KeyClass> = setOf(KeyClass.IconButton)
}

/** Which drop-shadow scale a neutral key uses: `--shadow-key` or the compact `--shadow-key-sm`. */
enum class KeySize { Regular, Small }

/** Interaction state that changes the key's material (focus is a separate, colour-free ring). */
enum class KeyState { Rest, Pressed, Disabled }

/** A radius meaning `border-radius: 50%` (the round `.chat-jump` cap). */
val KeyRadiusCircle: Dp = Dp.Infinity

@Immutable
data class KeyLook(
    val face: Color,
    val border: Color,
    val ink: Color,
    /** The full box-shadow list (inset bevels + side-wall + contact shadow), CSS order. */
    val shadows: List<CssShadow>,
    /** [KeyRadiusCircle] for a round cap. */
    val radius: Dp,
    /** `button:disabled { opacity: 0.48 }` (`.chat-send:disabled` 0.5). */
    val alpha: Float,
    /** Whether the execution slit `::before` is displayed (its width is `--key-slit`). */
    val slit: Boolean,
    /** Execution-slit opacity (0.55; 0.25 on a disabled key). */
    val slitAlpha: Float,
)

/** `button:disabled` opacity (globals.css:647). */
const val DisabledOpacity: Float = 0.48f

/** Studio's labelled keys: `border-radius: 0.625rem` (studio.css 260-264, a literal, not a token). */
val StudioKeyRadius: Dp = 10.dp

/** Where a rule was written; studio.css loads after globals.css. */
enum class CssFile { Globals, Studio }

/** What a key rule's selector requires beyond its classes. */
enum class KeyPseudo { None, Active, Disabled, On }

/** A rule inside `@media (max-width: 47.9375rem)` applies only to the phone layout (PLAN D10). */
enum class KeyMedia { All, Phone }

/** The mutable computed style a rule's declarations write into. */
class KeyComputed(
    var face: Color = Color.Transparent,
    var border: Color = Color.Transparent,
    var ink: Color = Color.Unspecified,
    var shadows: List<CssShadow> = emptyList(),
    var radius: Dp = 0.dp,
    var alpha: Float = 1f,
    var slit: Boolean = false,
    var slitAlpha: Float = 0.55f,
)

/** The context a declaration reads: the skin's tokens and the key's size variant. */
class KeyContext(val t: TetherTokens, val size: KeySize)

/**
 * One CSS rule (or one selector of a selector list, since each carries its own specificity).
 * [classes] must all be on the key; [specificity] is (a, b, c) packed as `a*10000 + b*100 + c`.
 */
class KeyRule(
    val file: CssFile,
    val line: Int,
    val classes: Set<KeyClass>,
    val specificity: Int,
    val pseudo: KeyPseudo = KeyPseudo.None,
    val media: KeyMedia = KeyMedia.All,
    val declare: KeyComputed.(KeyContext) -> Unit,
) {
    val selector: String
        get() = (if (file == CssFile.Studio) ":root:where(studio) " else ":root ") + classes.joinToString("") { ".${it.css}" } +
            when (pseudo) {
                KeyPseudo.None -> ""
                KeyPseudo.Active -> ":active:not(:disabled)"
                KeyPseudo.Disabled -> ":disabled"
                KeyPseudo.On -> ".is-on"
            }
}

private fun spec(b: Int, c: Int = 0): Int = b * 100 + c

private fun ctxContact(t: TetherTokens, a: Float) = t.contact.copy(alpha = a)

// Shared declaration blocks (each cites its source).
private val neutralRest: KeyComputed.(KeyContext) -> Unit = { c ->
    // globals.css 8592-8606
    border = c.t.keySide
    face = c.t.keyFace
    shadows = listOf(hardShadow(1.dp, c.t.litStrong, inset = true), hardShadow(0.dp, c.t.litSoft, x = 1.dp, inset = true)) +
        (if (c.size == KeySize.Small) c.t.css.shadowKeySm else c.t.css.shadowKey)
    ink = c.t.ink
}
private val neutralActive: KeyComputed.(KeyContext) -> Unit = { c ->
    // globals.css 8683-8696
    face = c.t.keyFaceDeep
    shadows = c.t.css.bevelPressed + c.t.css.shadowKeyPressed
}
private val primaryRest: KeyComputed.(KeyContext) -> Unit = { c ->
    // globals.css 8634-8641
    border = c.t.accentSide
    face = c.t.accent
    ink = c.t.accentInk
    shadows = listOf(
        hardShadow(1.dp, c.t.litFaint, inset = true),
        hardShadow(0.dp, c.t.litFaint, x = 1.dp, inset = true),
        hardShadow((-1).dp, ctxContact(c.t, 0.1f), x = (-1).dp, inset = true),
        hardShadow(3.dp, c.t.accentSide),
        softShadow(5.dp, 7.dp, ctxContact(c.t, 0.32f), spread = (-2).dp),
    )
}
private val primaryActive: KeyComputed.(KeyContext) -> Unit = { c ->
    // globals.css 8711-8717
    face = c.t.accentDeep
    shadows = listOf(
        softShadow(2.dp, 3.dp, c.t.pressShade, inset = true),
        hardShadow((-1).dp, c.t.litFaint, inset = true),
        hardShadow(1.dp, c.t.accentSide),
        softShadow(1.dp, 2.dp, ctxContact(c.t, 0.24f)),
    )
}
private val brickRest: KeyComputed.(KeyContext) -> Unit = { c ->
    // globals.css 8656-8664
    border = c.t.brickSide
    face = c.t.brick
    ink = c.t.accentInk
    shadows = listOf(
        hardShadow(1.dp, c.t.litFaint, inset = true),
        hardShadow((-1).dp, ctxContact(c.t, 0.1f), x = (-1).dp, inset = true),
        hardShadow(2.dp, c.t.brickSide),
        softShadow(4.dp, 6.dp, ctxContact(c.t, 0.3f), spread = (-2).dp),
    )
}
private val brickActive: KeyComputed.(KeyContext) -> Unit = { c ->
    // globals.css 8719-8726
    face = c.t.brickDeep
    shadows = listOf(
        softShadow(2.dp, 3.dp, c.t.pressShade, inset = true),
        hardShadow(1.dp, c.t.brickSide),
        softShadow(1.dp, 2.dp, ctxContact(c.t, 0.24f)),
    )
}
private val flatDisabled: KeyComputed.(KeyContext) -> Unit = { c ->
    // globals.css 8757-8769
    border = c.t.lineStrong
    face = c.t.keyFace
    ink = c.t.muted
    shadows = listOf(hardShadow(1.dp, c.t.keySide))
}
private val studioFlatKey: KeyComputed.(KeyContext) -> Unit = {
    // studio.css 260-264 (radius, box-shadow: none; the legend styling is the skin's key role)
    radius = StudioKeyRadius
    shadows = emptyList()
}
private val studioAccentKey: KeyComputed.(KeyContext) -> Unit = { c ->
    // studio.css:265-267
    face = c.t.accent
    ink = c.t.accentInk
    border = Color.Transparent
}
private val studioFlatDestructive: KeyComputed.(KeyContext) -> Unit = {
    // studio.css 489-497
    border = Color.Transparent
    shadows = emptyList()
}

private fun g(line: Int, classes: Set<KeyClass>, specificity: Int, pseudo: KeyPseudo = KeyPseudo.None, media: KeyMedia = KeyMedia.All, declare: KeyComputed.(KeyContext) -> Unit) =
    KeyRule(CssFile.Globals, line, classes, specificity, pseudo, media = media, declare = declare)

private fun s(line: Int, classes: Set<KeyClass>, specificity: Int, pseudo: KeyPseudo = KeyPseudo.None, declare: KeyComputed.(KeyContext) -> Unit) =
    KeyRule(CssFile.Studio, line, classes, specificity, pseudo, declare = declare)

private val Primary = setOf(KeyClass.ButtonPrimary)
private val Secondary = setOf(KeyClass.ButtonSecondary)
private val Danger = setOf(KeyClass.ButtonPrimary, KeyClass.ButtonDanger)
private val Deny = setOf(KeyClass.ChatApprovalDeny)
private val Send = setOf(KeyClass.ChatSend)
private val Interrupt = setOf(KeyClass.ChatInterrupt)
private val End = setOf(KeyClass.EndSession)
private val NewSession = setOf(KeyClass.NewSessionButton)
private val Attach = setOf(KeyClass.ChatAttachBtn)
private val Jump = setOf(KeyClass.ChatJump)
private val Icon = setOf(KeyClass.IconButton)

/**
 * Every rule that paints a key, one entry per selector (web source at PARITY_BASE 7d65611).
 * Specificity: `:root` and each class/pseudo-class count b; `:where()` counts nothing; an element
 * or pseudo-element counts c. `.chat-approval-actions` (the deny's ancestor) is counted in.
 */
val KeyRules: List<KeyRule> = listOf(
    // ── globals.css base rules (specificity (0,1,0) unless noted) ──
    g(641, emptySet(), spec(1, 1), KeyPseudo.Disabled) { alpha = DisabledOpacity }, // button:disabled
    g(755, Icon, spec(1)) { border = Color.Transparent; radius = it.t.radiusSm; face = Color.Transparent; ink = it.t.muted }, // colour inherits; hosts set --muted
    g(892, NewSession, spec(1)) { border = it.t.lineStrong; radius = it.t.radiusSm; face = it.t.graphiteRaised; shadows = it.t.css.edgeHighlight + it.t.css.shadowRaised; ink = it.t.white },
    g(1930, End, spec(1)) { border = Color.Transparent; radius = it.t.radiusSm; face = Color.Transparent; ink = it.t.muted },
    g(2297, Primary, spec(1)) { radius = it.t.radiusSm },
    g(2298, Secondary, spec(1)) { radius = it.t.radiusSm },
    g(2312, Primary, spec(1)) { border = Color.Transparent; face = it.t.violetStrong; shadows = listOf(hardShadow(1.dp, it.t.litStrong, inset = true)) + it.t.css.shadowRaised; ink = it.t.white },
    g(2323, Secondary, spec(1)) { border = it.t.lineStrong; face = Color.Transparent; ink = it.t.ink },
    g(4774, Jump, spec(1)) { border = it.t.lineStrong; radius = KeyRadiusCircle; face = it.t.graphiteRaised; ink = it.t.ink; shadows = it.t.css.shadowFloating },
    g(7107, Send, spec(1)) { border = it.t.violetStrong; radius = it.t.radiusMd; face = it.t.violetDeep; ink = it.t.white; shadows = listOf(hardShadow(1.dp, it.t.litStrong, inset = true)) + it.t.css.shadowRaised },
    g(7127, Send, spec(2), KeyPseudo.Disabled) { alpha = 0.5f },
    g(7128, Interrupt, spec(1)) { border = it.t.dangerEdge; face = it.t.dangerWash },
    g(7136, Attach, spec(1)) { border = it.t.line; radius = it.t.radiusMd; face = it.t.mineralDeep; ink = it.t.muted },

    // ── globals.css material layer (":root" rules) ──
    g(8592, Secondary, spec(2), declare = neutralRest),
    g(8593, NewSession, spec(2), declare = neutralRest),
    g(8597, Attach, spec(2), declare = neutralRest),
    g(8634, Primary, spec(2), declare = primaryRest),
    g(8635, Send, spec(2), declare = primaryRest),
    g(8656, Interrupt, spec(2), declare = brickRest),
    g(8657, Deny, spec(3), declare = brickRest),
    g(8658, Danger, spec(3), declare = brickRest),
    g(8659, End, spec(2), declare = brickRest),
    g(8675, Primary, spec(2)) { radius = it.t.radiusKey },
    g(8676, Secondary, spec(2)) { radius = it.t.radiusKey },
    g(8677, Send, spec(2)) { radius = it.t.radiusKey },
    g(8678, Interrupt, spec(2)) { radius = it.t.radiusKey },
    g(8679, NewSession, spec(2)) { radius = it.t.radiusKey },
    g(8680, End, spec(2)) { radius = it.t.radiusKey },
    g(8686, Secondary, spec(4), KeyPseudo.Active, declare = neutralActive),
    g(8687, NewSession, spec(3), KeyPseudo.Active, declare = neutralActive),
    g(8691, Attach, spec(3), KeyPseudo.Active, declare = neutralActive),
    g(8711, Primary, spec(4), KeyPseudo.Active, declare = primaryActive),
    g(8712, Send, spec(4), KeyPseudo.Active, declare = primaryActive),
    g(8719, Interrupt, spec(4), KeyPseudo.Active, declare = brickActive),
    g(8720, Deny, spec(5), KeyPseudo.Active, declare = brickActive),
    g(8721, Danger, spec(5), KeyPseudo.Active, declare = brickActive),
    g(8722, End, spec(4), KeyPseudo.Active, declare = brickActive),
    g(8734, Jump, spec(2)) { border = it.t.charcoalSide; face = it.t.charcoal; ink = it.t.utilityInk; shadows = listOf(hardShadow(1.dp, it.t.litSoft, inset = true), hardShadow(2.dp, it.t.charcoalSide)) + it.t.css.shadowFloating },
    g(8749, Jump, spec(3), KeyPseudo.Active) { face = oklabMix(it.t.charcoal, it.t.contact, 0.9f); shadows = listOf(softShadow(2.dp, 3.dp, it.t.pressShade, inset = true), hardShadow(1.dp, it.t.charcoalSide)) + it.t.css.shadowFloating },
    g(8757, Primary, spec(3), KeyPseudo.Disabled, declare = flatDisabled),
    g(8758, Secondary, spec(3), KeyPseudo.Disabled, declare = flatDisabled),
    g(8759, Send, spec(3), KeyPseudo.Disabled, declare = flatDisabled),
    g(8760, Interrupt, spec(3), KeyPseudo.Disabled, declare = flatDisabled),
    g(8983, Icon, spec(3), KeyPseudo.Active, declare = neutralActive), // icon controls (8983-8989)
    // Execution slit ::before (globals.css 9220-9253).
    g(9228, Primary, spec(2, 1)) { slit = true; slitAlpha = 0.55f },
    g(9229, Send, spec(2, 1)) { slit = true; slitAlpha = 0.55f },
    g(9231, Interrupt, spec(2, 1)) { slit = true; slitAlpha = 0.55f },
    g(9232, End, spec(2, 1)) { slit = true; slitAlpha = 0.55f },
    g(9250, Primary, spec(3, 1), KeyPseudo.Disabled) { slitAlpha = 0.25f },
    g(9251, Send, spec(3, 1), KeyPseudo.Disabled) { slitAlpha = 0.25f },
    g(9253, Interrupt, spec(3, 1), KeyPseudo.Disabled) { slitAlpha = 0.25f },
    // The composer paperclip (inside .chat-composer-toolbar): a round key at pill height on
    // desktop (11389-11406), the key radius on a phone (11941, inside the 47.9375rem query).
    g(11315, Attach, spec(2)) { border = it.t.keySide; radius = it.t.radiusKey },
    g(11389, Attach, spec(3)) {
        border = it.t.keySide
        radius = KeyRadiusCircle
        face = it.t.keyFace
        shadows = listOf(hardShadow(1.dp, it.t.litStrong, inset = true)) + it.t.css.shadowKeySm
        ink = it.t.muted
    },
    g(11402, Attach, spec(4), KeyPseudo.Active, declare = neutralActive),
    g(11941, Attach, spec(3), media = KeyMedia.Phone) { radius = it.t.radiusKey },
    // Latched (.is-on) keys: raised, the violet selected tone (globals.css 11048-11056).
    g(11049, emptySet(), spec(3), KeyPseudo.On) {
        face = it.t.violetWash
        ink = it.t.violet
        shadows = listOf(
            hardShadow(1.dp, it.t.litStrong, inset = true),
            CssShadow(inset = true, offsetX = 0.dp, offsetY = 0.dp, blur = 0.dp, spread = 1.dp, color = it.t.violetStrong),
        ) + it.t.css.shadowKeySm
    },

    // ── studio.css (loads after globals.css; `:root:where(...)` = (0,1,0) + classes) ──
    s(260, Primary, spec(2), declare = studioFlatKey),
    s(261, Secondary, spec(2), declare = studioFlatKey),
    s(262, Send, spec(2), declare = studioFlatKey),
    s(263, Interrupt, spec(2), declare = studioFlatKey),
    s(264, NewSession, spec(2), declare = studioFlatKey),
    s(265, Primary, spec(2), declare = studioAccentKey),
    s(266, Send, spec(2), declare = studioAccentKey),
    s(267, NewSession, spec(2), declare = studioAccentKey),
    s(277, Secondary, spec(2)) { border = it.t.lineStrong; face = it.t.graphite; ink = it.t.ink },
    s(271, Primary, spec(2, 1)) { slit = false },
    s(273, NewSession, spec(2, 1)) { slit = false },
    s(275, Send, spec(2, 1)) { slit = false },
    s(303, NewSession, spec(2)) { face = Color(0xFF365CDE) },
    s(389, Attach, spec(3)) { border = Color.Transparent; radius = 9.6.dp; face = it.t.graphiteRaised; shadows = emptyList() }, // .chat-composer-toolbar .chat-attach-btn
    s(489, Deny, spec(3), declare = studioFlatDestructive),
    s(491, Deny, spec(5), KeyPseudo.Active, declare = studioFlatDestructive),
    s(492, Danger, spec(3), declare = studioFlatDestructive),
    s(493, Danger, spec(5), KeyPseudo.Active, declare = studioFlatDestructive),
)

/** The rules that apply to [classes] in [state] for this skin, in cascade (application) order. */
fun matchingKeyRules(
    t: TetherTokens,
    classes: Set<KeyClass>,
    state: KeyState,
    selected: Boolean = false,
    layout: TetherLayoutClass = TetherLayoutClass.Phone,
): List<KeyRule> {
    return KeyRules.filter { r ->
        (r.media == KeyMedia.All || layout == TetherLayoutClass.Phone) &&
            classes.containsAll(r.classes) && when (r.pseudo) {
            KeyPseudo.None -> true
            KeyPseudo.Active -> state == KeyState.Pressed
            KeyPseudo.Disabled -> state == KeyState.Disabled
            KeyPseudo.On -> selected
        }
    }.sortedWith(compareBy<KeyRule>({ it.specificity }, { it.file.ordinal }, { it.line }))
}

/** Resolves a key's look: the cascade over [KeyRules] for its class set, skin and state. */
fun resolveKey(
    t: TetherTokens,
    classes: Set<KeyClass>,
    state: KeyState,
    selected: Boolean = false,
    size: KeySize = KeySize.Regular,
    layout: TetherLayoutClass = TetherLayoutClass.Phone,
): KeyLook {
    val c = KeyComputed(ink = t.ink)
    val ctx = KeyContext(t, size)
    for (rule in matchingKeyRules(t, classes, state, selected, layout)) rule.declare(c, ctx)
    return KeyLook(c.face, c.border, c.ink, c.shadows, c.radius, c.alpha, c.slit, c.slitAlpha)
}

/**
 * CSS `color-mix(in oklab, a p, b)` for opaque colours (the chat-jump press face). Exact: sRGB →
 * linear → OKLab (Björn Ottosson's matrices, as CSS Color 4 specifies), lerp, and back.
 */
fun oklabMix(a: Color, b: Color, pA: Float): Color {
    val la = a.toOklab()
    val lb = b.toOklab()
    val mix = FloatArray(3) { la[it] * pA + lb[it] * (1f - pA) }
    return fromOklab(mix, alpha = a.alpha * pA + b.alpha * (1f - pA))
}

private fun Color.toOklab(): FloatArray {
    fun lin(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    val r = lin(red); val g = lin(green); val b = lin(blue)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

private fun fromOklab(lab: FloatArray, alpha: Float): Color {
    val l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
    val m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
    val s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
    val l3 = l * l * l; val m3 = m * m * m; val s3 = s * s * s
    fun enc(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) 12.92f * v else (1.055f * v.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }
    return Color(
        red = enc(4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3),
        green = enc(-1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3),
        blue = enc(-0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3),
        alpha = alpha.coerceIn(0f, 1f),
    )
}
