package com.tether.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.ThemeFamily
import com.tether.app.ui.theme.tokensFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key cascade pinned as a TABLE: web class set × skin family × state → the computed values,
 * each row transcribed by hand from the CSS at PARITY_BASE (g = app/globals.css, s = app/studio.css,
 * the winning rule cited per row). Every row is checked in every skin of its family. The shadow
 * lists here are an independent transcription of the CSS, not the production building blocks.
 */
class KeyStyleTest {
    private enum class Fam { Instrument, Studio }

    private class Row(
        val source: String,
        val classes: Set<KeyClass>,
        val fam: Fam,
        val state: KeyState,
        val face: (TetherTokens) -> Color,
        val border: (TetherTokens) -> Color,
        val ink: (TetherTokens) -> Color,
        val shadows: (TetherTokens) -> List<CssShadow>,
        val travel: (TetherTokens) -> Dp = { 0.dp },
        val radius: (TetherTokens) -> Dp,
        val alpha: Float = 1f,
        val slit: Boolean = false,
        val slitAlpha: Float = 0.55f,
        val wear: KeyWear = KeyWear.None,
    )

    // ── Transcribed shadow lists ──
    private fun c(t: TetherTokens, a: Float) = t.contact.copy(alpha = a)
    private fun inset(y: Dp, color: Color, x: Dp = 0.dp, blur: Dp = 0.dp, spread: Dp = 0.dp) = CssShadow(true, x, y, blur, spread, color)
    private fun drop(y: Dp, color: Color, blur: Dp = 0.dp, spread: Dp = 0.dp) = CssShadow(false, 0.dp, y, blur, spread, color)

    /** g8592: inset 0 1px 0 lit-strong, inset 1px 0 0 lit-soft, var(--shadow-key). */
    private val neutral = { t: TetherTokens -> listOf(inset(1.dp, t.litStrong), inset(0.dp, t.litSoft, x = 1.dp)) + t.css.shadowKey }

    /** g8686: var(--bevel-pressed), var(--shadow-key-pressed). */
    private val neutralPressed = { t: TetherTokens -> t.css.bevelPressed + t.css.shadowKeyPressed }

    /** g8634: lit-faint ×2, inset -1px -1px contact/.1, 0 3px accent-side, 0 5px 7px -2px contact/.32. */
    private val primary = { t: TetherTokens ->
        listOf(
            inset(1.dp, t.litFaint), inset(0.dp, t.litFaint, x = 1.dp), inset((-1).dp, c(t, 0.1f), x = (-1).dp),
            drop(3.dp, t.accentSide), drop(5.dp, c(t, 0.32f), blur = 7.dp, spread = (-2).dp),
        )
    }

    /** g8711: inset 0 2px 3px press-shade, inset 0 -1px 0 lit-faint, 0 1px 0 accent-side, 0 1px 2px contact/.24. */
    private val primaryPressed = { t: TetherTokens ->
        listOf(inset(2.dp, t.pressShade, blur = 3.dp), inset((-1).dp, t.litFaint), drop(1.dp, t.accentSide), drop(1.dp, c(t, 0.24f), blur = 2.dp))
    }

    /** g8656: inset 0 1px 0 lit-faint, inset -1px -1px 0 contact/.1, 0 2px 0 brick-side, 0 4px 6px -2px contact/.3. */
    private val brick = { t: TetherTokens ->
        listOf(inset(1.dp, t.litFaint), inset((-1).dp, c(t, 0.1f), x = (-1).dp), drop(2.dp, t.brickSide), drop(4.dp, c(t, 0.3f), blur = 6.dp, spread = (-2).dp))
    }

    /** g8719: inset 0 2px 3px press-shade, 0 1px 0 brick-side, 0 1px 2px contact/.24. */
    private val brickPressed = { t: TetherTokens -> listOf(inset(2.dp, t.pressShade, blur = 3.dp), drop(1.dp, t.brickSide), drop(1.dp, c(t, 0.24f), blur = 2.dp)) }

    /** g8757: 0 1px 0 key-side. */
    private val flat = { t: TetherTokens -> listOf(drop(1.dp, t.keySide)) }
    private val jump = { t: TetherTokens -> listOf(inset(1.dp, t.litSoft), drop(2.dp, t.charcoalSide)) + t.css.shadowFloating }
    private val none = { _: TetherTokens -> emptyList<CssShadow>() }
    private val clear = { _: TetherTokens -> Color.Transparent }
    private val studioRadius = { _: TetherTokens -> 10.dp }
    private val keyRadius = { t: TetherTokens -> t.radiusKey }
    private val travel = { t: TetherTokens -> t.pressTravel }

    private val I = Fam.Instrument
    private val S = Fam.Studio
    private val R = KeyState.Rest
    private val P = KeyState.Pressed
    private val D = KeyState.Disabled

    private val table: List<Row> = listOf(
        // ── {button-primary} ──
        Row("g8634 + g8675 + g9228 + g8787", KeyClasses.ButtonPrimary, I, R, { it.accent }, { it.accentSide }, { it.accentInk }, primary, radius = keyRadius, slit = true, wear = KeyWear.Primary),
        Row("g8711 (0,4,0)", KeyClasses.ButtonPrimary, I, P, { it.accentDeep }, { it.accentSide }, { it.accentInk }, primaryPressed, travel, keyRadius, slit = true, wear = KeyWear.Primary),
        Row("g8757 + g641 + g9250", KeyClasses.ButtonPrimary, I, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = keyRadius, alpha = 0.48f, slit = true, slitAlpha = 0.25f, wear = KeyWear.Primary),
        Row("s260 + s265 + s271 (tie, studio later)", KeyClasses.ButtonPrimary, S, R, { it.accent }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8711 (0,4,0) beats s260; s265 border stays", KeyClasses.ButtonPrimary, S, P, { it.accentDeep }, clear, { it.accentInk }, primaryPressed, travel, studioRadius),
        Row("g8757 (0,3,0) beats s265 (0,2,0)", KeyClasses.ButtonPrimary, S, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = studioRadius, alpha = 0.48f, slitAlpha = 0.25f),

        // ── {button-secondary} ──
        Row("g8592 + g8676", KeyClasses.ButtonSecondary, I, R, { it.keyFace }, { it.keySide }, { it.ink }, neutral, radius = keyRadius),
        Row("g8686 (0,4,0)", KeyClasses.ButtonSecondary, I, P, { it.keyFaceDeep }, { it.keySide }, { it.ink }, neutralPressed, travel, keyRadius),
        Row("g8758", KeyClasses.ButtonSecondary, I, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = keyRadius, alpha = 0.48f),
        Row("s261 + s277", KeyClasses.ButtonSecondary, S, R, { it.graphite }, { it.lineStrong }, { it.ink }, none, radius = studioRadius),
        Row("g8686 (0,4,0); s277 border stays", KeyClasses.ButtonSecondary, S, P, { it.keyFaceDeep }, { it.lineStrong }, { it.ink }, neutralPressed, travel, studioRadius),
        Row("g8758 (0,3,0)", KeyClasses.ButtonSecondary, S, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = studioRadius, alpha = 0.48f),

        // ── {button-primary, button-danger} ──
        Row("g8658 (0,3,0) + g9228 + g8787", KeyClasses.ButtonDanger, I, R, { it.brick }, { it.brickSide }, { it.accentInk }, brick, radius = keyRadius, slit = true, wear = KeyWear.Primary),
        Row("g8721 (0,5,0)", KeyClasses.ButtonDanger, I, P, { it.brickDeep }, { it.brickSide }, { it.accentInk }, brickPressed, travel, keyRadius, slit = true, wear = KeyWear.Primary),
        Row("g8757 (0,3,0) ties g8658, later", KeyClasses.ButtonDanger, I, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = keyRadius, alpha = 0.48f, slit = true, slitAlpha = 0.25f, wear = KeyWear.Primary),
        Row("g8658 (0,3,0) face beats s265; s492 flat", KeyClasses.ButtonDanger, S, R, { it.brick }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("s493 (0,5,0) ties g8721, later; face from g8721", KeyClasses.ButtonDanger, S, P, { it.brickDeep }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8757 then s492 (0,3,0, later)", KeyClasses.ButtonDanger, S, D, { it.keyFace }, clear, { it.muted }, none, radius = studioRadius, alpha = 0.48f, slitAlpha = 0.25f),

        // ── {button-secondary, chat-approval-deny} ──
        Row("g8657 (0,3,0) beats g8592", KeyClasses.ApprovalDeny, I, R, { it.brick }, { it.brickSide }, { it.accentInk }, brick, radius = keyRadius),
        Row("g8720 (0,5,0)", KeyClasses.ApprovalDeny, I, P, { it.brickDeep }, { it.brickSide }, { it.accentInk }, brickPressed, travel, keyRadius),
        Row("g8758 (0,3,0) ties g8657, later: a flat grey secondary", KeyClasses.ApprovalDeny, I, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = keyRadius, alpha = 0.48f),
        Row("g8657 + s261 radius + s489 flat", KeyClasses.ApprovalDeny, S, R, { it.brick }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("s491 (0,5,0) ties g8720, later", KeyClasses.ApprovalDeny, S, P, { it.brickDeep }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8758 then s489 (0,3,0, later)", KeyClasses.ApprovalDeny, S, D, { it.keyFace }, clear, { it.muted }, none, radius = studioRadius, alpha = 0.48f),

        // ── {chat-send} ──
        Row("g8635 + g8677 + g9229 + g8848 (phone wear)", KeyClasses.ChatSend, I, R, { it.accent }, { it.accentSide }, { it.accentInk }, primary, radius = keyRadius, slit = true, wear = KeyWear.SendCompact),
        Row("g8712 (0,4,0)", KeyClasses.ChatSend, I, P, { it.accentDeep }, { it.accentSide }, { it.accentInk }, primaryPressed, travel, keyRadius, slit = true, wear = KeyWear.SendCompact),
        Row("g8759 + g7127 opacity 0.5 + g9251", KeyClasses.ChatSend, I, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = keyRadius, alpha = 0.5f, slit = true, slitAlpha = 0.25f, wear = KeyWear.SendCompact),
        Row("s262 + s266 + s275", KeyClasses.ChatSend, S, R, { it.accent }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8712 (0,4,0)", KeyClasses.ChatSend, S, P, { it.accentDeep }, clear, { it.accentInk }, primaryPressed, travel, studioRadius),
        Row("g8759 + g7127", KeyClasses.ChatSend, S, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = studioRadius, alpha = 0.5f, slitAlpha = 0.25f),

        // ── {chat-send, chat-interrupt} ──
        Row("g8656 ties g8635, later + g9231 + g8786", KeyClasses.ChatInterrupt, I, R, { it.brick }, { it.brickSide }, { it.accentInk }, brick, radius = keyRadius, slit = true, wear = KeyWear.SendCompact),
        Row("g8719 ties g8712, later", KeyClasses.ChatInterrupt, I, P, { it.brickDeep }, { it.brickSide }, { it.accentInk }, brickPressed, travel, keyRadius, slit = true, wear = KeyWear.SendCompact),
        Row("g8760 + g7127 + g9253", KeyClasses.ChatInterrupt, I, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = keyRadius, alpha = 0.5f, slit = true, slitAlpha = 0.25f, wear = KeyWear.SendCompact),
        Row("s266 (.chat-send accent) ties g8656, later: BLUE", KeyClasses.ChatInterrupt, S, R, { it.accent }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8719 (0,4,0) ties g8712, later; s266 border stays", KeyClasses.ChatInterrupt, S, P, { it.brickDeep }, clear, { it.accentInk }, brickPressed, travel, studioRadius),
        Row("g8760 (0,3,0)", KeyClasses.ChatInterrupt, S, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = studioRadius, alpha = 0.5f, slitAlpha = 0.25f),

        // ── {end-session} (no Studio rule: studio.css 355-365 excludes it, issue #177) ──
        Row("g8659 + g8680 + g9232", KeyClasses.EndSession, I, R, { it.brick }, { it.brickSide }, { it.accentInk }, brick, radius = keyRadius, slit = true),
        Row("g8722", KeyClasses.EndSession, I, P, { it.brickDeep }, { it.brickSide }, { it.accentInk }, brickPressed, travel, keyRadius, slit = true),
        Row("not in g8757's list: g641 only", KeyClasses.EndSession, I, D, { it.brick }, { it.brickSide }, { it.accentInk }, brick, radius = keyRadius, alpha = 0.48f, slit = true),
        Row("g8659", KeyClasses.EndSession, S, R, { it.brick }, { it.brickSide }, { it.accentInk }, brick, radius = keyRadius, slit = true),
        Row("g8722", KeyClasses.EndSession, S, P, { it.brickDeep }, { it.brickSide }, { it.accentInk }, brickPressed, travel, keyRadius, slit = true),

        // ── {new-session-button} ──
        Row("g8593 + g8679 + g8788", KeyClasses.NewSession, I, R, { it.keyFace }, { it.keySide }, { it.ink }, neutral, radius = keyRadius, wear = KeyWear.NewSession),
        Row("g8687 (0,3,0)", KeyClasses.NewSession, I, P, { it.keyFaceDeep }, { it.keySide }, { it.ink }, neutralPressed, travel, keyRadius, wear = KeyWear.NewSession),
        Row("s264 + s267 + s273 + s303 #365cde", KeyClasses.NewSession, S, R, { Color(0xFF365CDE) }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8687 (0,3,0) beats s303 (0,2,0)", KeyClasses.NewSession, S, P, { it.keyFaceDeep }, clear, { it.accentInk }, neutralPressed, travel, studioRadius),

        // ── {chat-attach-btn} (in .chat-composer-toolbar), phone layout ──
        Row("g11389 (0,3,0) + g11941 phone radius", KeyClasses.Attach, I, R, { it.keyFace }, { it.keySide }, { it.muted },
            { t -> listOf(inset(1.dp, t.litStrong)) + t.css.shadowKeySm }, radius = keyRadius),
        Row("g11402 (0,4,0)", KeyClasses.Attach, I, P, { it.keyFaceDeep }, { it.keySide }, { it.muted }, neutralPressed, travel, keyRadius),
        Row("s389 (0,3,0) ties g11389/g11941, later; ink from g11389", KeyClasses.Attach, S, R, { it.graphiteRaised }, clear, { it.muted }, none, radius = { 9.6.dp }),
        Row("g11402 (0,4,0) beats s389", KeyClasses.Attach, S, P, { it.keyFaceDeep }, clear, { it.muted }, neutralPressed, travel, { 9.6.dp }),

        // ── {chat-jump} ──
        Row("g8734 + g4774 radius 50%", KeyClasses.ChatJump, I, R, { it.charcoal }, { it.charcoalSide }, { it.utilityInk }, jump, radius = { KeyRadiusCircle }),
        Row("g8734 + g641", KeyClasses.ChatJump, S, D, { it.charcoal }, { it.charcoalSide }, { it.utilityInk }, jump, radius = { KeyRadiusCircle }, alpha = 0.48f),

        // ── {icon-button} ──
        Row("g755", KeyClasses.IconButton, I, R, clear, clear, { it.muted }, none, radius = { it.radiusSm }),
        Row("g8983 (0,3,0)", KeyClasses.IconButton, S, P, { it.keyFaceDeep }, clear, { it.muted }, neutralPressed, travel, { it.radiusSm }),
    )

    @Test fun cascadeTable() {
        var checked = 0
        for (row in table) {
            val skins = TetherSkin.entries.filter { (it.family == ThemeFamily.Studio) == (row.fam == Fam.Studio) }
            for (skin in skins) {
                val t = tokensFor(skin)
                val look = resolveKey(t, row.classes, row.state)
                val at = "${row.classes.joinToString(" ") { it.css }} ${skin.id} ${row.state} [${row.source}]"
                assertEquals("$at face", row.face(t), look.face)
                assertEquals("$at border", row.border(t), look.border)
                assertEquals("$at ink", row.ink(t), look.ink)
                assertEquals("$at shadows", row.shadows(t), look.shadows)
                assertEquals("$at travel", row.travel(t), look.travel)
                assertEquals("$at radius", row.radius(t), look.radius)
                assertEquals("$at alpha", row.alpha, look.alpha)
                assertEquals("$at slit", row.slit, look.slit)
                assertEquals("$at slitAlpha", row.slitAlpha, look.slitAlpha)
                assertEquals("$at wear", row.wear, look.wear)
                checked++
            }
        }
        assertTrue("rows × skins checked: $checked", checked >= 150)
    }

    @Test fun everyAppClassSetHasRestAndPressedRowsInBothFamilies() {
        val sets = listOf(
            KeyClasses.ButtonPrimary, KeyClasses.ButtonSecondary, KeyClasses.ButtonDanger, KeyClasses.ApprovalDeny,
            KeyClasses.ChatSend, KeyClasses.ChatInterrupt, KeyClasses.EndSession, KeyClasses.NewSession, KeyClasses.Attach,
        )
        for (set in sets) for (fam in Fam.entries) for (state in listOf(R, P)) {
            assertTrue("$set $fam $state", table.any { it.classes == set && it.fam == fam && it.state == state })
        }
    }

    @Test fun cascadeOrderIsSpecificityThenFileThenLine() {
        val t = tokensFor(TetherSkin.Studio)
        val rules = matchingKeyRules(t, KeyClasses.ChatInterrupt, KeyState.Rest)
        // g8656 `:root .chat-interrupt` and s266 `:root:where() .chat-send` tie at (0,2,0); studio is later.
        val brick = rules.indexOfFirst { it.file == CssFile.Globals && it.line == 8656 }
        val accent = rules.indexOfFirst { it.file == CssFile.Studio && it.line == 266 }
        assertTrue(brick in 0 until accent)
        val sorted = rules.sortedWith(compareBy({ it.specificity }, { it.file.ordinal }, { it.line }))
        assertEquals(sorted, rules)
    }

    @Test fun desktopPaperclipIsARoundKey() {
        val t = tokensFor(TetherSkin.Machine)
        assertEquals(KeyRadiusCircle, resolveKey(t, KeyClasses.Attach, KeyState.Rest, layout = TetherLayoutClass.Expanded).radius)
        assertEquals(t.radiusKey, resolveKey(t, KeyClasses.Attach, KeyState.Rest, layout = TetherLayoutClass.Phone).radius)
    }

    @Test fun desktopSendWearsTheFullComposition() {
        val t = tokensFor(TetherSkin.Tactile)
        assertEquals(KeyWear.Send, resolveKey(t, KeyClasses.ChatSend, KeyState.Rest, layout = TetherLayoutClass.Expanded).wear)
        assertEquals(KeyWear.SendCompact, resolveKey(t, KeyClasses.ChatInterrupt, KeyState.Rest).wear)
    }

    @Test fun latchedKeysCarryTheVioletSelectedTone() {
        for (skin in TetherSkin.entries) {
            val t = tokensFor(skin)
            val look = resolveKey(t, KeyClasses.ButtonSecondary, KeyState.Rest, selected = true)
            assertEquals(t.violetWash, look.face)
            assertEquals(t.violet, look.ink)
            assertTrue(look.shadows.any { it.inset && it.spread == 1.dp && it.color == t.violetStrong })
            // Pressing a latched key shows the press (0,4,0) over the latch (0,3,0).
            assertEquals(t.keyFaceDeep, resolveKey(t, KeyClasses.ButtonSecondary, KeyState.Pressed, selected = true).face)
        }
    }

    @Test fun smallKeysUseTheCompactDropScale() {
        val t = tokensFor(TetherSkin.Tactile)
        val look = resolveKey(t, KeyClasses.ButtonSecondary, KeyState.Rest, size = KeySize.Small)
        assertEquals(t.css.shadowKeySm, look.shadows.drop(2))
    }

    @Test fun oklabMixMatchesCssColorMix() {
        val same = oklabMix(Color(0xFF336699), Color(0xFF336699), 0.3f)
        assertEquals(0x33 / 255f, same.red, 0.002f)
        assertEquals(0x66 / 255f, same.green, 0.002f)
        // color-mix(in oklab, black 50%, white) = oklab(0.5 0 0) = rgb(99 99 99).
        val grey = oklabMix(Color.Black, Color.White, 0.5f)
        assertEquals(99 / 255f, grey.red, 1.5f / 255f)
        assertEquals(grey.red, grey.blue, 0.002f)
    }
}
