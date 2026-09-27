package com.tether.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.ThemeFamily
import com.tether.app.ui.theme.tokensFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The key cascade: globals.css material layer, Studio's flat overrides, and who wins where. */
class KeyStyleTest {
    private val instrument = TetherSkin.entries.filter { it.family != ThemeFamily.Studio }
    private val studio = TetherSkin.entries.filter { it.family == ThemeFamily.Studio }

    @Test fun instrumentSecondaryRestIsMoldedFaceOverSideWall() {
        for (skin in instrument) {
            val t = tokensFor(skin)
            val look = resolveKey(t, KeyVariant.Secondary, KeyState.Rest)
            assertEquals(skin.id, t.keyFace, look.face)
            assertEquals(skin.id, t.keySide, look.border)
            assertEquals(skin.id, t.radiusKey, look.radius)
            assertEquals(skin.id, 2 + t.css.shadowKey.size, look.shadows.size)
            assertTrue(skin.id, look.shadows.take(2).all { it.inset })
            assertEquals(skin.id, 0.dp, look.travel)
        }
    }

    @Test fun smallKeysUseTheCompactDropScale() {
        val t = tokensFor(TetherSkin.Tactile)
        val look = resolveKey(t, KeyVariant.Secondary, KeyState.Rest, size = KeySize.Small)
        assertEquals(t.css.shadowKeySm, look.shadows.drop(2))
    }

    @Test fun pressedKeysTravelThePressDepthAndDarken() {
        for (skin in TetherSkin.entries) {
            val t = tokensFor(skin)
            val secondary = resolveKey(t, KeyVariant.Secondary, KeyState.Pressed)
            assertEquals(skin.id, t.pressTravel, secondary.travel)
            assertEquals(skin.id, t.keyFaceDeep, secondary.face)
            assertEquals(skin.id, t.css.bevelPressed + t.css.shadowKeyPressed, secondary.shadows)
        }
        assertEquals(0.dp, tokensFor(TetherSkin.Studio).pressTravel)
    }

    @Test fun studioFlattensRestingKeys() {
        for (skin in studio) {
            val t = tokensFor(skin)
            val secondary = resolveKey(t, KeyVariant.Secondary, KeyState.Rest)
            assertEquals(skin.id, t.graphite, secondary.face)
            assertEquals(skin.id, emptyList<Any>(), secondary.shadows)
            assertEquals(skin.id, StudioKeyRadius, secondary.radius)
            val primary = resolveKey(t, KeyVariant.Primary, KeyState.Rest)
            assertEquals(skin.id, t.accent, primary.face)
            assertEquals(skin.id, Color.Transparent, primary.border)
            assertTrue(skin.id, primary.shadows.isEmpty())
        }
    }

    @Test fun globalsPressedPrimaryOutranksStudiosFlatRule() {
        for (skin in studio) {
            val t = tokensFor(skin)
            val look = resolveKey(t, KeyVariant.Primary, KeyState.Pressed)
            assertEquals(skin.id, t.accentDeep, look.face)
            assertEquals(skin.id, 4, look.shadows.size)
        }
    }

    @Test fun brickRolesShareOneGlobalsLookOutsideStudio() {
        for (skin in instrument) {
            val t = tokensFor(skin)
            val looks = listOf(KeyVariant.Deny, KeyVariant.Danger, KeyVariant.Interrupt, KeyVariant.EndSession)
                .map { resolveKey(t, it, KeyState.Rest) }
            assertTrue(skin.id, looks.all { it == looks.first() })
            assertEquals(t.brick, looks.first().face)
            assertEquals(t.brickSide, looks.first().border)
            assertEquals(4, looks.first().shadows.size)
            val pressed = resolveKey(t, KeyVariant.Interrupt, KeyState.Pressed)
            assertEquals(t.brickDeep, pressed.face)
            assertEquals(t.pressTravel, pressed.travel)
        }
    }

    @Test fun studioDenyAndDangerAreFlatInEveryState() {
        for (skin in studio) {
            val t = tokensFor(skin)
            for (role in listOf(KeyVariant.Deny, KeyVariant.Danger)) {
                val rest = resolveKey(t, role, KeyState.Rest)
                assertTrue("${skin.id} $role", rest.shadows.isEmpty())
                assertEquals("${skin.id} $role", Color.Transparent, rest.border)
                assertEquals("${skin.id} $role", t.brick, rest.face)
                val pressed = resolveKey(t, role, KeyState.Pressed)
                assertTrue("${skin.id} $role", pressed.shadows.isEmpty())
                assertEquals("${skin.id} $role", t.brickDeep, pressed.face)
                assertEquals("${skin.id} $role", 0.dp, pressed.travel)
            }
            // Danger is a .button-primary: 0.625rem; deny keeps the skin's key radius.
            assertEquals(StudioKeyRadius, resolveKey(t, KeyVariant.Danger, KeyState.Rest).radius)
            assertEquals(t.radiusKey, resolveKey(t, KeyVariant.Deny, KeyState.Rest).radius)
        }
    }

    @Test fun studioInterruptKeepsItsBorderAndTheGlobalsPress() {
        for (skin in studio) {
            val t = tokensFor(skin)
            val rest = resolveKey(t, KeyVariant.Interrupt, KeyState.Rest)
            assertEquals(skin.id, t.brickSide, rest.border)
            assertTrue(skin.id, rest.shadows.isEmpty())
            assertEquals(skin.id, StudioKeyRadius, rest.radius)
            val pressed = resolveKey(t, KeyVariant.Interrupt, KeyState.Pressed)
            assertEquals(skin.id, t.brickDeep, pressed.face)
            assertEquals(skin.id, 3, pressed.shadows.size)
            assertTrue(skin.id, pressed.shadows.first().inset)
        }
    }

    @Test fun studioEndSessionStaysRaised() {
        for (skin in studio) {
            val t = tokensFor(skin)
            val rest = resolveKey(t, KeyVariant.EndSession, KeyState.Rest)
            assertEquals(skin.id, t.brickSide, rest.border)
            assertEquals(skin.id, 4, rest.shadows.size)
            assertEquals(skin.id, hardShadow(2.dp, t.brickSide), rest.shadows[2])
            assertEquals(skin.id, 3, resolveKey(t, KeyVariant.EndSession, KeyState.Pressed).shadows.size)
        }
    }

    @Test fun disabledBrickRoles() {
        for (skin in TetherSkin.entries) {
            val t = tokensFor(skin)
            // Deny and end-session are outside the flat-disabled rule: their own look, faded.
            for (role in listOf(KeyVariant.Deny, KeyVariant.EndSession)) {
                val look = resolveKey(t, role, KeyState.Disabled)
                assertEquals("${skin.id} $role", t.brick, look.face)
                assertEquals("${skin.id} $role", DisabledOpacity, look.alpha)
            }
            val interrupt = resolveKey(t, KeyVariant.Interrupt, KeyState.Disabled)
            assertEquals(skin.id, listOf(hardShadow(1.dp, t.keySide)), interrupt.shadows)
            val danger = resolveKey(t, KeyVariant.Danger, KeyState.Disabled)
            assertEquals(skin.id, t.keyFace, danger.face)
            assertEquals(skin.id, t.muted, danger.ink)
            if (skin.family == ThemeFamily.Studio) {
                // Studio's flat danger rule ties the disabled rule and comes later.
                assertTrue(skin.id, danger.shadows.isEmpty())
                assertEquals(skin.id, Color.Transparent, danger.border)
            } else {
                assertEquals(skin.id, listOf(hardShadow(1.dp, t.keySide)), danger.shadows)
            }
        }
    }

    @Test fun slitRoles() {
        assertEquals(setOf(KeyVariant.Primary, KeyVariant.Danger, KeyVariant.Interrupt, KeyVariant.EndSession), SlitKeys)
    }

    @Test fun disabledKeysSitFlatAtTheDisabledOpacityInEverySkin() {
        for (skin in TetherSkin.entries) {
            val t = tokensFor(skin)
            for (variant in listOf(KeyVariant.Primary, KeyVariant.Secondary, KeyVariant.Interrupt)) {
                val look = resolveKey(t, variant, KeyState.Disabled)
                assertEquals("${skin.id} $variant", DisabledOpacity, look.alpha)
                assertEquals("${skin.id} $variant", t.keyFace, look.face)
                assertEquals("${skin.id} $variant", t.muted, look.ink)
                assertEquals("${skin.id} $variant", listOf(hardShadow(1.dp, t.keySide)), look.shadows)
                assertEquals("${skin.id} $variant", 0.25f, look.slitAlpha)
            }
            // The charcoal utility cap and quiet icon keys keep their own look, only faded.
            val utility = resolveKey(t, KeyVariant.Utility, KeyState.Disabled)
            assertEquals(t.charcoal, utility.face)
            assertEquals(DisabledOpacity, utility.alpha)
            assertEquals(DisabledOpacity, resolveKey(t, KeyVariant.Quiet, KeyState.Disabled).alpha)
        }
    }

    @Test fun latchedKeysCarryTheVioletSelectedTone() {
        for (skin in TetherSkin.entries) {
            val t = tokensFor(skin)
            val look = resolveKey(t, KeyVariant.Secondary, KeyState.Rest, selected = true)
            assertEquals(t.violetWash, look.face)
            assertEquals(t.violet, look.ink)
            assertTrue(look.shadows.any { it.inset && it.spread == 1.dp && it.color == t.violetStrong })
            // Pressing a latched key shows the press, not the latch.
            assertEquals(t.keyFaceDeep, resolveKey(t, KeyVariant.Secondary, KeyState.Pressed, selected = true).face)
        }
    }

    @Test fun quietKeysAreBareAtRestOnTheSmallRadius() {
        val t = tokensFor(TetherSkin.Machine)
        val look = resolveKey(t, KeyVariant.Quiet, KeyState.Rest)
        assertEquals(Color.Transparent, look.face)
        assertTrue(look.shadows.isEmpty())
        assertEquals(t.radiusSm, look.radius)
        assertEquals(t.muted, look.ink)
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
