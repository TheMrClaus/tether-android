package com.tether.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Tether material token set — a 1:1 port of the CSS custom properties in
 * aidash/app/globals.css (see specs/visual-spec.md §1). Components read these
 * directly via [LocalTetherTokens]; Material3's ColorScheme is only mapped for
 * interop (dialogs, text selection) and must not be used for custom surfaces.
 */

enum class TetherThemeFamily(val id: String, val isDark: Boolean) {
    Machine("machine", isDark = true),
    Night("night", isDark = true),
    Tactile("tactile", isDark = false),
    Precision("precision", isDark = false),
}

/** The persisted preference: an explicit family or follow-the-system. */
enum class ThemeChoice(val id: String, val label: String) {
    System("system", "System"),
    Machine("machine", "Machine"),
    Night("night", "Night"),
    Tactile("tactile", "Tactile"),
    Precision("precision", "Precision");

    companion object {
        fun fromId(id: String?): ThemeChoice = when (id) {
            // Legacy stored value from the web app maps to the original theme.
            "quiet" -> Machine
            else -> entries.firstOrNull { it.id == id } ?: System
        }
    }
}

fun ThemeChoice.resolve(systemDark: Boolean): TetherThemeFamily = when (this) {
    ThemeChoice.System -> if (systemDark) TetherThemeFamily.Machine else TetherThemeFamily.Precision
    ThemeChoice.Machine -> TetherThemeFamily.Machine
    ThemeChoice.Night -> TetherThemeFamily.Night
    ThemeChoice.Tactile -> TetherThemeFamily.Tactile
    ThemeChoice.Precision -> TetherThemeFamily.Precision
}

/** Theme-invariant structural dimensions (visual-spec §1.1 "Structural"). */
object TetherDimens {
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    val spaceXl = 24.dp
    val radiusSm = 6.dp
    val radiusMd = 10.dp
    val radiusLg = 14.dp

    /** Chat bubble max width fraction on mobile. */
    const val bubbleMaxFraction = 0.94f

    const val durationFastMs = 140
    const val durationSlowMs = 200
    const val touchTarget = 44 // dp
    val touchTargetDp = 44.dp
}

@Immutable
data class TetherTokens(
    val family: TetherThemeFamily,
    // Surfaces
    val mineral: Color,
    val mineralDeep: Color,
    val graphite: Color,
    val graphiteRaised: Color,
    val slate: Color,
    // Seams
    val line: Color,
    val lineStrong: Color,
    val seamLip: Color,
    // Text
    val white: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    // Violet — ONLY focus / selected / waiting-for-user
    val violet: Color,
    val violetStrong: Color,
    val violetDeep: Color,
    val violetWash: Color,
    val focusGlow: Color,
    val selectionBg: Color,
    // Status
    val running: Color,
    val danger: Color,
    val warning: Color,
    val dangerEdge: Color,
    val dangerWash: Color,
    // Keys
    val keyFace: Color,
    val keyFaceHover: Color,
    val keyFaceDeep: Color,
    val keySide: Color,
    val accent: Color,
    val accentHover: Color,
    val accentDeep: Color,
    val accentSide: Color,
    val accentInk: Color,
    val accentWash: Color,
    val brick: Color,
    val brickDeep: Color,
    val brickSide: Color,
    val brickWash: Color,
    val amber: Color,
    val amberWash: Color,
    val charcoal: Color,
    val charcoalSide: Color,
    val utilityInk: Color,
    // Light & shade
    val contact: Color,
    val tintRgb: Color,
    val tintBoost: Float,
    // Key geometry
    val pressTravel: Dp,
    val radiusKey: Dp,
    val keySlit: Dp,
    /** Uppercase key-legend tracking in em. */
    val keyTracking: Float,
    // Overlays / transcript
    val scrim: Color,
    val userBubbleBg: Color,
    val userBubbleBorder: Color,
    val userBubbleInk: Color,
    val diffAddBg: Color,
    val diffAddInk: Color,
    val diffDelBg: Color,
    val diffDelInk: Color,
    val attentionBorder: Color,
    val attentionBg: Color,
    val attentionInk: Color,
    val questionBorder: Color,
    val questionBg: Color,
    val questionInk: Color,
    val dropOverlay: Color,
) {
    private fun tint(alpha: Float): Color =
        tintRgb.copy(alpha = (alpha * tintBoost).coerceAtMost(1f))

    val tintXs: Color get() = tint(0.015f)
    val tintSm: Color get() = tint(0.03f)
    val tintMd: Color get() = tint(0.06f)
    val tintLg: Color get() = tint(0.1f)
    val tintLine: Color get() = tint(0.12f)
}

val MachineTokens = TetherTokens(
    family = TetherThemeFamily.Machine,
    mineral = Color(0xFF0B0F10),
    mineralDeep = Color(0xFF070A0B),
    graphite = Color(0xFF111517),
    graphiteRaised = Color(0xFF1B2225),
    slate = Color(0xFF283135),
    line = Color(0xFF293236),
    lineStrong = Color(0xFF454F53),
    seamLip = Color(0xFF667277),
    white = Color(0xFFEEF2F3),
    ink = Color(0xFFD2D9DB),
    muted = Color(0xFF9BA6AA),
    faint = Color(0xFF808B8F),
    violet = Color(0xFF8B7FF0),
    violetStrong = Color(0xFF6F5FE8),
    violetDeep = Color(0xFFB4AAFF),
    violetWash = Color(0xFF1C1A33),
    focusGlow = Color(0x4D6F5FE8),
    selectionBg = Color(0xFF2F2A5C),
    running = Color(0xFF5FD3D8),
    danger = Color(0xFFE2685B),
    warning = Color(0xFFE0A53A),
    dangerEdge = Color(0x80E2685B),
    dangerWash = Color(0xFF24100E),
    keyFace = Color(0xFF1B2225),
    keyFaceHover = Color(0xFF222A2E),
    keyFaceDeep = Color(0xFF141A1C),
    keySide = Color(0xFF05080A),
    accent = Color(0xFF2E6D63),
    accentHover = Color(0xFF357A6F),
    accentDeep = Color(0xFF245A51),
    accentSide = Color(0xFF10322D),
    accentInk = Color(0xFFFFFFFF),
    accentWash = Color(0xFF15302C),
    brick = Color(0xFFA83C31),
    brickDeep = Color(0xFFBD4839),
    brickSide = Color(0xFF5F211A),
    brickWash = Color(0xFF2A1512),
    amber = Color(0xFFC98F2C),
    amberWash = Color(0xFF2B2210),
    charcoal = Color(0xFF2A3336),
    charcoalSide = Color(0xFF0C1113),
    utilityInk = Color(0xFFDFE6E8),
    contact = Color(0xFF000000),
    tintRgb = Color(0xFFFFFFFF),
    tintBoost = 1f,
    pressTravel = 2.dp,
    radiusKey = 8.dp,
    keySlit = 3.dp,
    keyTracking = 0.06f,
    scrim = Color(0xB3020506),
    userBubbleBg = Color(0xFF1D3B37),
    userBubbleBorder = Color(0xFF2D5B54),
    userBubbleInk = Color(0xFFE4EFED),
    diffAddBg = Color(0xFF14291F),
    diffAddInk = Color(0xFF86D3A3),
    diffDelBg = Color(0xFF2D1614),
    diffDelInk = Color(0xFFEB8E82),
    attentionBorder = Color(0xFF7A5C1C),
    attentionBg = Color(0xFF211A0D),
    attentionInk = Color(0xFFE6B455),
    questionBorder = Color(0xFF35506E),
    questionBg = Color(0xFF111A24),
    questionInk = Color(0xFF8FB4DD),
    dropOverlay = Color(0xDB1C1A33),
)

val TactileTokens = TetherTokens(
    family = TetherThemeFamily.Tactile,
    mineral = Color(0xFFD6D8D3),
    mineralDeep = Color(0xFFEEF0EA),
    graphite = Color(0xFFE7E8E3),
    graphiteRaised = Color(0xFFF0F1EC),
    slate = Color(0xFFC9CCC3),
    line = Color(0xFFC0C3BA),
    lineStrong = Color(0xFFA6AA9F),
    seamLip = Color(0xFF8F9389),
    white = Color(0xFF23262B),
    ink = Color(0xFF33373C),
    muted = Color(0xFF4C514C),
    faint = Color(0xFF575C53),
    violet = Color(0xFF5A4FB4),
    violetStrong = Color(0xFF5747C8),
    violetDeep = Color(0xFF4A3FA0),
    violetWash = Color(0xFFE5E3F3),
    focusGlow = Color(0x2E5747C8),
    selectionBg = Color(0xFFCFCBE8),
    running = Color(0xFF35693F),
    danger = Color(0xFFA34F44),
    warning = Color(0xFF7D5C15),
    dangerEdge = Color(0x80A34F44),
    dangerWash = Color(0xFFF1E3DF),
    keyFace = Color(0xFFEFF0EB),
    keyFaceHover = Color(0xFFF6F7F2),
    keyFaceDeep = Color(0xFFE0E2DB),
    keySide = Color(0xFFB3B6AC),
    accent = Color(0xFF67785A),
    accentHover = Color(0xFF607052),
    accentDeep = Color(0xFF5B6B4F),
    accentSide = Color(0xFF46543C),
    accentInk = Color(0xFFFFFFFF),
    accentWash = Color(0xFFB9C5AC),
    brick = Color(0xFFA34F44),
    brickDeep = Color(0xFF8E4238),
    brickSide = Color(0xFF6F382F),
    brickWash = Color(0xFFEDDCD7),
    amber = Color(0xFFB8892E),
    amberWash = Color(0xFFEFE8D0),
    charcoal = Color(0xFF3F444A),
    charcoalSide = Color(0xFF24272B),
    utilityInk = Color(0xFFEEF0EA),
    contact = Color(0xFF2D302A),
    tintRgb = Color(0xFF2D302A),
    tintBoost = 1.6f,
    pressTravel = 3.dp,
    radiusKey = 9.6.dp,
    keySlit = 0.dp,
    keyTracking = 0.05f,
    scrim = Color(0x6B2D302A),
    userBubbleBg = Color(0xFFB9C5AC),
    userBubbleBorder = Color(0xFF9AA98C),
    userBubbleInk = Color(0xFF262A26),
    diffAddBg = Color(0xFFDBE6D2),
    diffAddInk = Color(0xFF375833),
    diffDelBg = Color(0xFFECD9D4),
    diffDelInk = Color(0xFF83392F),
    attentionBorder = Color(0xFFC8A24B),
    attentionBg = Color(0xFFEFE8D0),
    attentionInk = Color(0xFF7D5C15),
    questionBorder = Color(0xFF93A2BD),
    questionBg = Color(0xFFE4E9F1),
    questionInk = Color(0xFF44608E),
    dropOverlay = Color(0xD9E5E3F3),
)

val PrecisionTokens = TetherTokens(
    family = TetherThemeFamily.Precision,
    mineral = Color(0xFFE0E5E5),
    mineralDeep = Color(0xFFECEFF0),
    graphite = Color(0xFFF2F4F3),
    graphiteRaised = Color(0xFFF8F9F9),
    slate = Color(0xFFD2D9DA),
    line = Color(0xFFCDD5D6),
    lineStrong = Color(0xFFBCC4C6),
    seamLip = Color(0xFFA6B0B2),
    white = Color(0xFF1B2428),
    ink = Color(0xFF2A343A),
    muted = Color(0xFF4B585D),
    faint = Color(0xFF59666B),
    violet = Color(0xFF5546C9),
    violetStrong = Color(0xFF4B3CC4),
    violetDeep = Color(0xFF3D3299),
    violetWash = Color(0xFFE7E4F8),
    focusGlow = Color(0x384B3CC4),
    selectionBg = Color(0xFFD5D0F2),
    running = Color(0xFF14707D),
    danger = Color(0xFFB3392C),
    warning = Color(0xFF8A6412),
    dangerEdge = Color(0x80B3392C),
    dangerWash = Color(0xFFF6E4E1),
    keyFace = Color(0xFFF7F8F8),
    keyFaceHover = Color(0xFFFDFDFD),
    keyFaceDeep = Color(0xFFE4E9E9),
    keySide = Color(0xFFB9C2C4),
    accent = Color(0xFF3D7D6E),
    accentHover = Color(0xFF366F62),
    accentDeep = Color(0xFF2F6558),
    accentSide = Color(0xFF255249),
    accentInk = Color(0xFFFFFFFF),
    accentWash = Color(0xFFDCEBE6),
    brick = Color(0xFFB3392C),
    brickDeep = Color(0xFF9C3025),
    brickSide = Color(0xFF7D271E),
    brickWash = Color(0xFFF7E3E0),
    amber = Color(0xFFC9962F),
    amberWash = Color(0xFFF5EDD9),
    charcoal = Color(0xFF3A464B),
    charcoalSide = Color(0xFF202A2E),
    utilityInk = Color(0xFFF2F4F3),
    contact = Color(0xFF1B2428),
    tintRgb = Color(0xFF1B2428),
    tintBoost = 1.6f,
    pressTravel = 2.dp,
    radiusKey = 8.dp,
    keySlit = 3.dp,
    keyTracking = 0.06f,
    scrim = Color(0x661B2428),
    userBubbleBg = Color(0xFFCFE4DD),
    userBubbleBorder = Color(0xFF93BDB0),
    userBubbleInk = Color(0xFF17302A),
    diffAddBg = Color(0xFFD9EBE2),
    diffAddInk = Color(0xFF1F5C43),
    diffDelBg = Color(0xFFF4DEDB),
    diffDelInk = Color(0xFF8D2F24),
    attentionBorder = Color(0xFFCBA653),
    attentionBg = Color(0xFFF6EFDC),
    attentionInk = Color(0xFF7D5A10),
    questionBorder = Color(0xFF9DB1C9),
    questionBg = Color(0xFFE7EDF4),
    questionInk = Color(0xFF3D5C85),
    dropOverlay = Color(0xDBE7E4F8),
)

val NightTokens = TetherTokens(
    family = TetherThemeFamily.Night,
    mineral = Color(0xFF121413),
    mineralDeep = Color(0xFF0B0D0C),
    graphite = Color(0xFF171918),
    graphiteRaised = Color(0xFF242725),
    slate = Color(0xFF31352F),
    line = Color(0xFF2C302B),
    lineStrong = Color(0xFF454A42),
    seamLip = Color(0xFF565C53),
    white = Color(0xFFECEEE6),
    ink = Color(0xFFD6D8CD),
    muted = Color(0xFF9BA093),
    faint = Color(0xFF8D9284),
    violet = Color(0xFF8F83E6),
    violetStrong = Color(0xFF7264DD),
    violetDeep = Color(0xFFB3A9FF),
    violetWash = Color(0xFF1F1C33),
    focusGlow = Color(0x4D7264DD),
    selectionBg = Color(0xFF322C57),
    running = Color(0xFF8FBF7A),
    danger = Color(0xFFD4685A),
    warning = Color(0xFFD3A04A),
    dangerEdge = Color(0x80D4685A),
    dangerWash = Color(0xFF26110F),
    keyFace = Color(0xFF242725),
    keyFaceHover = Color(0xFF2C302D),
    keyFaceDeep = Color(0xFF1B1E1C),
    keySide = Color(0xFF080908),
    accent = Color(0xFF626E49),
    accentHover = Color(0xFF6C7952),
    accentDeep = Color(0xFF55603E),
    accentSide = Color(0xFF333A24),
    accentInk = Color(0xFFF4F6EC),
    accentWash = Color(0xFF242A1A),
    brick = Color(0xFF9E4034),
    brickDeep = Color(0xFFB04A3D),
    brickSide = Color(0xFF5A231C),
    brickWash = Color(0xFF2A1613),
    amber = Color(0xFFB8892E),
    amberWash = Color(0xFF221D0F),
    charcoal = Color(0xFF33372F),
    charcoalSide = Color(0xFF0C0D0B),
    utilityInk = Color(0xFFE2E4D9),
    contact = Color(0xFF000000),
    tintRgb = Color(0xFFFFFFFF),
    tintBoost = 1f,
    pressTravel = 3.dp,
    radiusKey = 9.6.dp,
    keySlit = 0.dp,
    keyTracking = 0.06f,
    scrim = Color(0x9E060706),
    userBubbleBg = Color(0xFF2F3826),
    userBubbleBorder = Color(0xFF4A5639),
    userBubbleInk = Color(0xFFE9ECDF),
    diffAddBg = Color(0xFF1C2617),
    diffAddInk = Color(0xFFA2C78C),
    diffDelBg = Color(0xFF2B1614),
    diffDelInk = Color(0xFFE08B7D),
    attentionBorder = Color(0xFF6F5622),
    attentionBg = Color(0xFF221D0F),
    attentionInk = Color(0xFFD3A04A),
    questionBorder = Color(0xFF3C4A63),
    questionBg = Color(0xFF151A22),
    questionInk = Color(0xFF9DB2D1),
    dropOverlay = Color(0xDB1F1C33),
)

fun tokensFor(family: TetherThemeFamily): TetherTokens = when (family) {
    TetherThemeFamily.Machine -> MachineTokens
    TetherThemeFamily.Night -> NightTokens
    TetherThemeFamily.Tactile -> TactileTokens
    TetherThemeFamily.Precision -> PrecisionTokens
}

val LocalTetherTokens = staticCompositionLocalOf { MachineTokens }
