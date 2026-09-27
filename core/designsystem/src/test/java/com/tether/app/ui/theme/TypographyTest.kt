package com.tether.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.tether.app.core.designsystem.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web type scale (PLAN T3.2), table-driven per skin. Every expected value is written out
 * from the web CSS (tether 356b456: app/globals.css + app/studio.css, phone width
 * `max-width: 47.9375rem`) with the line it cascades from — independent of Typography.kt, so a
 * wrong citation or a wrong token read there fails here.
 */
class TypographyTest {

    private enum class Face { Ui, Mono }

    /** rem, weight, letter-spacing em, line-height (null = normal), uppercase, tabular-nums. */
    private data class Spec(
        val face: Face,
        val rem: Float,
        val weight: Int,
        val trackingEm: Float = 0f,
        val lineHeight: Float? = null,
        val uppercase: Boolean = false,
        val tabular: Boolean = false,
    )

    private val instrumentSkins = listOf(TetherSkin.Tactile, TetherSkin.Night, TetherSkin.Precision, TetherSkin.Machine)
    private val studioSkins = listOf(TetherSkin.Studio, TetherSkin.StudioDark)

    /** --key-label-tracking: globals.css:436/552 (tactile, night), 205/320 (machine, precision), studio.css:78. */
    private fun keyTracking(skin: TetherSkin): Float = when (skin) {
        TetherSkin.Tactile, TetherSkin.Night -> 0.05f
        TetherSkin.Precision, TetherSkin.Machine -> 0.06f
        TetherSkin.Studio, TetherSkin.StudioDark -> 0f
    }

    private fun instrument(skin: TetherSkin): Map<String, Spec> = mapOf(
        // body — globals.css:609-610
        "body" to Spec(Face.Ui, 1f, 400),
        // .chat-bubble — globals.css:11272 (0.92rem), 11743 (line-height 1.65)
        "chatBody" to Spec(Face.Ui, 0.92f, 400, lineHeight = 1.65f),
        // .chat-input — globals.css:8512 (phone 1rem), 6741 (line-height 1.5)
        "composerInput" to Spec(Face.Ui, 1f, 400, lineHeight = 1.5f),
        // .workspace-title-row h1 — globals.css:11859 (1.05rem), 1686-1687 (680, -0.01em), 11742 (1.45)
        "screenTitle" to Spec(Face.Ui, 1.05f, 680, -0.01f, 1.45f),
        // .empty-workspace h1 — globals.css:11257 (clamp floor 1.7rem, 640, -0.035em), 2285 (1.18)
        "displayTitle" to Spec(Face.Ui, 1.7f, 640, -0.035f, 1.18f),
        // .md-h — globals.css:4915-4918
        "markdownH3" to Spec(Face.Ui, 1.05f, 680, lineHeight = 1.3f),
        "markdownH4" to Spec(Face.Ui, 0.98f, 680, lineHeight = 1.3f),
        "markdownH5" to Spec(Face.Ui, 0.92f, 680, lineHeight = 1.3f),
        // .session-item-copy strong — globals.css:11113 (0.84rem, 650, -0.005em), 11736 (1.4)
        "listTitle" to Spec(Face.Ui, 0.84f, 650, -0.005f, 1.4f),
        // .md-pre code — globals.css:5032-5035
        "codeBlock" to Spec(Face.Mono, 0.8f, 400, lineHeight = 1.5f),
        // .chat-msg-time — globals.css:4859-4860 (0.65rem, tabular-nums); line-height inherited (11743)
        "timestamp" to Spec(Face.Ui, 0.65f, 400, lineHeight = 1.65f, tabular = true),
        // .usage-totals strong — globals.css:11748 (mono, 1.35rem), 11573 (640, tabular-nums, -0.01em)
        "numeral" to Spec(Face.Mono, 1.35f, 640, -0.01f, tabular = true),
        // .section-label — globals.css:2273-2276
        "sectionLabel" to Spec(Face.Ui, 0.67f, 720, 0.1f, uppercase = true),
        // :root .status-badge — globals.css:11169-11173
        "statusLabel" to Spec(Face.Mono, 0.6f, 700, 0.08f, uppercase = true),
        // .button-primary — globals.css:2307-2308; etched legends 9211-9212
        "keyLabel" to Spec(Face.Ui, 0.8f, 680, keyTracking(skin), uppercase = true),
    )

    private fun studio(skin: TetherSkin): Map<String, Spec> = instrument(skin) + mapOf(
        // studio.css:455 (phone 0.9rem), 372 (line-height 1.8)
        "chatBody" to Spec(Face.Ui, 0.9f, 400, lineHeight = 1.8f),
        // studio.css:386-387 (focused textarea: 0.925rem / 1.6)
        "composerInput" to Spec(Face.Ui, 0.925f, 400, lineHeight = 1.6f),
        // studio.css:352 (740, -0.025em), 452 (phone 0.925rem); line-height globals.css:11742
        "screenTitle" to Spec(Face.Ui, 0.925f, 740, -0.025f, 1.45f),
        // studio.css:331 (0.78rem, 600, 1.45); tracking globals.css:11113 not reset
        "listTitle" to Spec(Face.Ui, 0.78f, 600, -0.005f, 1.45f),
        // studio.css:375 (--font-ui, 0.64rem); line-height inherited from the Studio bubble (372)
        "timestamp" to Spec(Face.Ui, 0.64f, 400, lineHeight = 1.8f, tabular = true),
        // studio.css:279 (--font-ui, none, 0, 0.75rem; weight 720 from globals.css:2274)
        "sectionLabel" to Spec(Face.Ui, 0.75f, 720),
        // studio.css:354 (font: 600 0.65rem var(--font-ui); none; 0)
        "statusLabel" to Spec(Face.Ui, 0.65f, 600),
        // --key-label-transform none / tracking 0 — studio.css:77-78
        "keyLabel" to Spec(Face.Ui, 0.8f, 680, 0f),
    )

    private fun actual(t: TetherTypography): Map<String, Pair<TextStyle, Boolean>> = mapOf(
        "body" to (t.body to false),
        "chatBody" to (t.chatBody to false),
        "composerInput" to (t.composerInput to false),
        "screenTitle" to (t.screenTitle to false),
        "displayTitle" to (t.displayTitle to false),
        "markdownH3" to (t.markdownH3 to false),
        "markdownH4" to (t.markdownH4 to false),
        "markdownH5" to (t.markdownH5 to false),
        "listTitle" to (t.listTitle to false),
        "codeBlock" to (t.codeBlock to false),
        "timestamp" to (t.timestamp to false),
        "numeral" to (t.numeral to false),
        "sectionLabel" to (t.sectionLabel.style to t.sectionLabel.uppercase),
        "statusLabel" to (t.statusLabel.style to t.statusLabel.uppercase),
        "keyLabel" to (t.keyLabel.style to t.keyLabel.uppercase),
    )

    private fun expected(skin: TetherSkin) = if (skin in studioSkins) studio(skin) else instrument(skin)

    private fun em(v: Float) = TextUnit(v, TextUnitType.Em)

    @Test
    fun everyRoleMatchesTheWebCssInAllSixSkins() {
        assertEquals(TetherSkin.entries.toSet(), (instrumentSkins + studioSkins).toSet())
        for (skin in TetherSkin.entries) {
            val t = typographyFor(skin)
            val want = expected(skin)
            val got = actual(t)
            assertEquals("$skin roles", want.keys, got.keys)
            for ((role, spec) in want) {
                val (style, uppercase) = got.getValue(role)
                val at = "${skin.id}.$role"
                val family = if (spec.face == Face.Ui) Manrope else JetBrainsMono
                assertSame("$at family", family, style.fontFamily)
                assertEquals("$at size sp", spec.rem * 16f, style.fontSize.value, 1e-4f)
                assertTrue("$at size unit", style.fontSize.type == TextUnitType.Sp)
                assertEquals("$at weight", spec.weight, style.fontWeight?.weight)
                assertEquals("$at tracking", em(spec.trackingEm), style.letterSpacing)
                if (spec.lineHeight == null) {
                    assertEquals("$at line-height normal", TextUnit.Unspecified, style.lineHeight)
                } else {
                    assertEquals("$at line-height", em(spec.lineHeight), style.lineHeight)
                }
                assertEquals("$at half-leading", CssLineHeight, style.lineHeightStyle)
                assertEquals("$at uppercase", spec.uppercase, uppercase)
                if (spec.tabular) {
                    assertEquals("$at tnum", "tnum", style.fontFeatureSettings)
                } else {
                    assertNull("$at no font features", style.fontFeatureSettings)
                }
            }
        }
    }

    @Test
    fun studioLabelsDropUppercaseAndTracking() {
        for (skin in studioSkins) {
            val t = typographyFor(skin)
            for (label in listOf(t.sectionLabel, t.statusLabel, t.keyLabel)) {
                assertFalse("${skin.id} uppercase", label.uppercase)
                assertEquals("${skin.id} tracking", em(0f), label.style.letterSpacing)
                assertSame("${skin.id} --font-ui", Manrope, label.style.fontFamily)
                assertEquals("Send", label.format("Send"))
            }
        }
        for (skin in instrumentSkins) {
            val t = typographyFor(skin)
            assertEquals("SEND", t.keyLabel.format("Send"))
            assertTrue(t.sectionLabel.uppercase && t.statusLabel.uppercase)
        }
    }

    @Test
    fun keyLabelReadsTheKeyLabelTokens() {
        for (skin in TetherSkin.entries) {
            val k = typographyFor(skin).keyLabel
            assertEquals(skin.tokens.keyLabelTracking, k.style.letterSpacing)
            assertEquals(skin.tokens.keyLabelTransform == "uppercase", k.uppercase)
        }
    }

    @Test
    fun fontStacksResolveToTheBundledFaces() {
        for (skin in TetherSkin.entries) {
            val t = typographyFor(skin)
            assertSame("${skin.id} mono", JetBrainsMono, t.mono)
            assertSame("${skin.id} ui", Manrope, t.ui)
        }
        // Studio is the only family defining --font-ui (studio.css:47); instrument skins use body's stack.
        assertEquals(studioSkins.toSet(), TetherSkin.entries.filter { it.tokens.fontUi != null }.toSet())
        assertSame(Manrope, fontFamilyForStack(TetherTypography.BODY_FONT_STACK))
        assertSame(JetBrainsMono, fontFamilyForStack("\"JetBrains Mono Variable\", ui-monospace"))
        assertSame(FontFamily.Monospace, fontFamilyForStack("\"Nope\", ui-monospace"))
        assertSame(FontFamily.SansSerif, fontFamilyForStack("-apple-system, \"Segoe UI\""))
    }

    @Test
    fun inlineCodeIsRelativeMono() {
        val span = typographyFor(TetherSkin.Machine).codeInline
        assertSame(JetBrainsMono, span.fontFamily)
        assertEquals(em(0.85f), span.fontSize) // .md-code 0.85em — globals.css:4953
    }

    @Test
    fun cssHalfLeadingIsCentred() {
        assertEquals(LineHeightStyle.Alignment.Center, CssLineHeight.alignment)
        assertEquals(LineHeightStyle.Trim.None, CssLineHeight.trim)
    }

    @Test
    fun everyFontEntryPinsItsWeightOnTheWghtAxis() {
        for ((family, res) in listOf(Manrope to R.font.manrope_variable, JetBrainsMono to R.font.jetbrains_mono_variable)) {
            val fonts = (family as FontListFontFamily).fonts.map { it as ResourceFont }
            assertEquals(WebFontWeights, fonts.map { it.weight.weight })
            for (f in fonts) {
                assertEquals(res, f.resId)
                val settings = f.variationSettings.settings
                assertEquals(listOf("wght"), settings.map { it.axisName })
                assertEquals(f.weight.weight.toFloat(), settings.single().toVariationValue(null), 0f)
            }
        }
    }

    @Test
    fun everyRoleWeightIsADeclaredInstance() {
        for (skin in TetherSkin.entries) {
            val t = typographyFor(skin)
            for ((role, pair) in actual(t)) {
                assertTrue("${skin.id}.$role weight declared", pair.first.fontWeight!!.weight in WebFontWeights)
            }
        }
    }
}
