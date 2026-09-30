package com.tether.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.tokensFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key cascade pinned as a TABLE: web class set × state → the computed values, each row
 * transcribed by hand from the CSS at PARITY_BASE (g = app/globals.css, s = app/studio.css, the
 * winning rule cited per row; Studio cascades over globals.css). Every row is checked in both
 * Studio skins. The shadow lists here are an independent transcription of the CSS, not the
 * production building blocks.
 */
class KeyStyleTest {

    private class Row(
        val source: String,
        val classes: Set<KeyClass>,
        val state: KeyState,
        val face: (TetherTokens) -> Color,
        val border: (TetherTokens) -> Color,
        val ink: (TetherTokens) -> Color,
        val shadows: (TetherTokens) -> List<CssShadow>,
        val radius: (TetherTokens) -> Dp,
        val alpha: Float = 1f,
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

    private val R = KeyState.Rest
    private val P = KeyState.Pressed
    private val D = KeyState.Disabled

    private val table: List<Row> = listOf(
        // ── {button-primary} ──
        Row("s260 + s265 + s271 (tie, studio later)", KeyClasses.ButtonPrimary, R, { it.accent }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8711 (0,4,0) beats s260; s265 border stays", KeyClasses.ButtonPrimary, P, { it.accentDeep }, clear, { it.accentInk }, primaryPressed, studioRadius),
        Row("g8757 (0,3,0) beats s265 (0,2,0)", KeyClasses.ButtonPrimary, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = studioRadius, alpha = 0.48f),

        // ── {button-secondary} ──
        Row("s261 + s277", KeyClasses.ButtonSecondary, R, { it.graphite }, { it.lineStrong }, { it.ink }, none, radius = studioRadius),
        Row("g8686 (0,4,0); s277 border stays", KeyClasses.ButtonSecondary, P, { it.keyFaceDeep }, { it.lineStrong }, { it.ink }, neutralPressed, studioRadius),
        Row("g8758 (0,3,0)", KeyClasses.ButtonSecondary, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = studioRadius, alpha = 0.48f),

        // ── {button-primary, button-danger} ──
        Row("g8658 (0,3,0) face beats s265; s492 flat", KeyClasses.ButtonDanger, R, { it.brick }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("s493 (0,5,0) ties g8721, later; face from g8721", KeyClasses.ButtonDanger, P, { it.brickDeep }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8757 then s492 (0,3,0, later)", KeyClasses.ButtonDanger, D, { it.keyFace }, clear, { it.muted }, none, radius = studioRadius, alpha = 0.48f),

        // ── {button-secondary, chat-approval-deny} ──
        Row("g8657 + s261 radius + s489 flat", KeyClasses.ApprovalDeny, R, { it.brick }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("s491 (0,5,0) ties g8720, later", KeyClasses.ApprovalDeny, P, { it.brickDeep }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8758 then s489 (0,3,0, later)", KeyClasses.ApprovalDeny, D, { it.keyFace }, clear, { it.muted }, none, radius = studioRadius, alpha = 0.48f),

        // ── {chat-send} ──
        Row("s262 + s266 + s275", KeyClasses.ChatSend, R, { it.accent }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8712 (0,4,0)", KeyClasses.ChatSend, P, { it.accentDeep }, clear, { it.accentInk }, primaryPressed, studioRadius),
        Row("g8759 + g7127", KeyClasses.ChatSend, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = studioRadius, alpha = 0.5f),

        // ── {chat-send, chat-interrupt} ──
        Row("s266 (.chat-send accent) ties g8656, later: BLUE", KeyClasses.ChatInterrupt, R, { it.accent }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8719 (0,4,0) ties g8712, later; s266 border stays", KeyClasses.ChatInterrupt, P, { it.brickDeep }, clear, { it.accentInk }, brickPressed, studioRadius),
        Row("g8760 (0,3,0)", KeyClasses.ChatInterrupt, D, { it.keyFace }, { it.lineStrong }, { it.muted }, flat, radius = studioRadius, alpha = 0.5f),

        // ── {end-session} (no Studio rule: studio.css 355-365 excludes it, issue #177) ──
        Row("g8659", KeyClasses.EndSession, R, { it.brick }, { it.brickSide }, { it.accentInk }, brick, radius = keyRadius),
        Row("g8722", KeyClasses.EndSession, P, { it.brickDeep }, { it.brickSide }, { it.accentInk }, brickPressed, keyRadius),

        // ── {new-session-button} ──
        Row("s264 + s267 + s273 + s303 #365cde", KeyClasses.NewSession, R, { Color(0xFF365CDE) }, clear, { it.accentInk }, none, radius = studioRadius),
        Row("g8687 (0,3,0) beats s303 (0,2,0)", KeyClasses.NewSession, P, { it.keyFaceDeep }, clear, { it.accentInk }, neutralPressed, studioRadius),

        // ── {chat-attach-btn} (in .chat-composer-toolbar), phone layout ──
        Row("s389 (0,3,0) ties g11389/g11941, later; ink from g11389", KeyClasses.Attach, R, { it.graphiteRaised }, clear, { it.muted }, none, radius = { 9.6.dp }),
        Row("g11402 (0,4,0) beats s389", KeyClasses.Attach, P, { it.keyFaceDeep }, clear, { it.muted }, neutralPressed, { 9.6.dp }),

        // ── {chat-jump} ──
        Row("g8734 + g641", KeyClasses.ChatJump, D, { it.charcoal }, { it.charcoalSide }, { it.utilityInk }, jump, radius = { KeyRadiusCircle }, alpha = 0.48f),

        // ── {icon-button} ──
        Row("g8983 (0,3,0)", KeyClasses.IconButton, P, { it.keyFaceDeep }, clear, { it.muted }, neutralPressed, { it.radiusSm }),
    )

    @Test fun cascadeTable() {
        var checked = 0
        for (row in table) {
            for (skin in TetherSkin.entries) {
                val t = tokensFor(skin)
                val look = resolveKey(t, row.classes, row.state)
                val at = "${row.classes.joinToString(" ") { it.css }} ${skin.id} ${row.state} [${row.source}]"
                assertEquals("$at face", row.face(t), look.face)
                assertEquals("$at border", row.border(t), look.border)
                assertEquals("$at ink", row.ink(t), look.ink)
                assertEquals("$at shadows", row.shadows(t), look.shadows)
                assertEquals("$at radius", row.radius(t), look.radius)
                assertEquals("$at alpha", row.alpha, look.alpha)
                checked++
            }
        }
        assertTrue("rows × skins checked: $checked", checked >= 50)
    }

    @Test fun everyAppClassSetHasRestAndPressedRows() {
        val sets = listOf(
            KeyClasses.ButtonPrimary, KeyClasses.ButtonSecondary, KeyClasses.ButtonDanger, KeyClasses.ApprovalDeny,
            KeyClasses.ChatSend, KeyClasses.ChatInterrupt, KeyClasses.EndSession, KeyClasses.NewSession, KeyClasses.Attach,
        )
        for (set in sets) for (state in listOf(R, P)) {
            assertTrue("$set $state", table.any { it.classes == set && it.state == state })
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

    /** s389 (0,3,0) is later than g11389's round cap and g11941's phone radius: 0.6rem at every width. */
    @Test fun studioPaperclipIsTheSameKeyAtEveryWidth() {
        for (skin in TetherSkin.entries) {
            val t = tokensFor(skin)
            assertEquals(9.6.dp, resolveKey(t, KeyClasses.Attach, KeyState.Rest, layout = TetherLayoutClass.Expanded).radius)
            assertEquals(9.6.dp, resolveKey(t, KeyClasses.Attach, KeyState.Rest, layout = TetherLayoutClass.Phone).radius)
        }
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
