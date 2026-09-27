package com.tether.app.ui.shell

import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.theme.GeneratedTokens
import com.tether.app.ui.theme.ThemeFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The desktop column geometry against lib/panel-widths.mjs (tether @ PARITY_BASE; the file is
 * unchanged through 0e6e862) and the `--rail-width` / `--inspector-width` cascade.
 */
class PanelWidthGeometryTest {
    private val rail = PanelKind.Rail
    private val inspector = PanelKind.Inspector

    /** panel-widths.mjs:30-33 limits; :81-87 bounds (the rem floor beats a smaller vw ceiling). */
    @Test fun boundsAreTheRemFloorAndTheSmallerOfTheRemAndVwCeilings() {
        assertEquals(PanelBounds(224, 480), PanelWidthGeometry.bounds(rail, 1280)) // min(30rem, 512)
        assertEquals(PanelBounds(224, 360), PanelWidthGeometry.bounds(rail, 900)) // 40vw
        assertEquals(PanelBounds(224, 480), PanelWidthGeometry.bounds(rail, 2000))
        assertEquals(PanelBounds(224, 224), PanelWidthGeometry.bounds(rail, 500)) // 40vw = 200 < 14rem
        assertEquals(PanelBounds(208, 448), PanelWidthGeometry.bounds(inspector, 1280)) // 35vw = 448
        assertEquals(PanelBounds(208, 448), PanelWidthGeometry.bounds(inspector, 1600)) // 28rem
        assertEquals(PanelBounds(208, 315), PanelWidthGeometry.bounds(inspector, 900))
    }

    /** panel-widths.mjs:91-95: rounded (JS half-up), clamped, junk to the floor. */
    @Test fun clampRoundsIntoTheBoundsAndJunkGoesToTheFloor() {
        assertEquals(224, PanelWidthGeometry.clamp(rail, 100.0, 1280))
        assertEquals(480, PanelWidthGeometry.clamp(rail, 999.0, 1280))
        assertEquals(300, PanelWidthGeometry.clamp(rail, 300.4, 1280))
        assertEquals(301, PanelWidthGeometry.clamp(rail, 300.5, 1280))
        assertEquals(224, PanelWidthGeometry.clamp(rail, Double.NaN, 1280))
        assertEquals(224, PanelWidthGeometry.clamp(rail, Double.POSITIVE_INFINITY, 1280))
        assertEquals(360, PanelWidthGeometry.clamp(rail, 400.0, 900))
    }

    /** panel-widths.mjs:113-117: the rail grows rightwards, the inspector leftwards. */
    @Test fun dragMovesTheColumnEdge() {
        assertEquals(304, PanelWidthGeometry.fromDrag(rail, 264.0, 40.0, 1280))
        assertEquals(224, PanelWidthGeometry.fromDrag(rail, 264.0, -400.0, 1280))
        assertEquals(480, PanelWidthGeometry.fromDrag(rail, 264.0, 900.0, 1280))
        assertEquals(232, PanelWidthGeometry.fromDrag(inspector, 272.0, 40.0, 1600))
        assertEquals(312, PanelWidthGeometry.fromDrag(inspector, 272.0, -40.0, 1600))
    }

    /** panel-widths.mjs:124-133: 16px, Shift 64px; the arrow moves the EDGE. */
    @Test fun arrowKeysMoveTheEdge() {
        assertEquals(16, PanelWidthGeometry.keyDelta(rail, PanelWidthGeometry.Arrow.Right, shift = false))
        assertEquals(-16, PanelWidthGeometry.keyDelta(rail, PanelWidthGeometry.Arrow.Left, shift = false))
        assertEquals(64, PanelWidthGeometry.keyDelta(rail, PanelWidthGeometry.Arrow.Right, shift = true))
        assertEquals(-16, PanelWidthGeometry.keyDelta(inspector, PanelWidthGeometry.Arrow.Right, shift = false))
        assertEquals(64, PanelWidthGeometry.keyDelta(inspector, PanelWidthGeometry.Arrow.Left, shift = true))
        assertEquals(16, PanelWidthGeometry.STEP_PX)
        assertEquals(64, PanelWidthGeometry.LARGE_STEP_PX)
    }

    /** panel-widths.mjs:66-74: a stored width is read fail-soft; out-of-range numbers are KEPT. */
    @Test fun storedWidthsParseFailSoft() {
        assertEquals(300, PanelWidthGeometry.parseStored(300))
        assertEquals(300, PanelWidthGeometry.parseStored("300"))
        assertEquals(13, PanelWidthGeometry.parseStored(12.6))
        assertEquals(0, PanelWidthGeometry.parseStored(-5))
        assertEquals(8192, PanelWidthGeometry.parseStored(90_000))
        assertNull(PanelWidthGeometry.parseStored(""))
        assertNull(PanelWidthGeometry.parseStored("  "))
        assertNull(PanelWidthGeometry.parseStored("wide"))
        assertNull(PanelWidthGeometry.parseStored(Double.NaN))
        assertNull(PanelWidthGeometry.parseStored(null))
        assertNull(PanelWidthGeometry.parseStored(true))
    }

    /**
     * The theme defaults (no stored width): instrument rail 18rem / 16.5rem at 48-100rem / 20rem
     * from 100rem (globals.css:70, 11856, 11727); inspector 16.5rem / 17rem from 90rem (:71,
     * 11727); Studio 17rem / 18rem everywhere (studio.css:48-49).
     */
    @Test fun defaultsFollowTheCascade() {
        val machine = ThemeFamily.Precision
        assertEquals(288, PanelWidthGeometry.defaultWidth(rail, machine, 700))
        assertEquals(264, PanelWidthGeometry.defaultWidth(rail, machine, 900))
        assertEquals(264, PanelWidthGeometry.defaultWidth(rail, machine, 1280))
        assertEquals(264, PanelWidthGeometry.defaultWidth(rail, machine, 1500)) // 11856 beats 11727
        assertEquals(320, PanelWidthGeometry.defaultWidth(rail, machine, 1600))
        assertEquals(264, PanelWidthGeometry.defaultWidth(inspector, machine, 1280))
        assertEquals(272, PanelWidthGeometry.defaultWidth(inspector, machine, 1600))
        for (w in listOf(900, 1280, 1600, 1920)) {
            assertEquals(272, PanelWidthGeometry.defaultWidth(rail, ThemeFamily.Studio, w))
            assertEquals(288, PanelWidthGeometry.defaultWidth(inspector, ThemeFamily.Studio, w))
        }
    }

    /** The defaults agree with the generated token export (D9): base values and the @media ones. */
    @Test fun defaultsAgreeWithTheGeneratedTokens() {
        assertEquals(GeneratedTokens.Machine.railWidth.value.toInt(), PanelWidthGeometry.defaultWidth(rail, ThemeFamily.Precision, 700))
        assertEquals(GeneratedTokens.Machine.inspectorWidth.value.toInt(), PanelWidthGeometry.defaultWidth(inspector, ThemeFamily.Precision, 1280))
        assertEquals(GeneratedTokens.Studio.railWidth.value.toInt(), PanelWidthGeometry.defaultWidth(rail, ThemeFamily.Studio, 1280))
        assertEquals(GeneratedTokens.StudioDark.inspectorWidth.value.toInt(), PanelWidthGeometry.defaultWidth(inspector, ThemeFamily.Studio, 1600))
        val media = GeneratedTokens.responsive.associateBy { it.name to it.media }
        assertEquals(media.getValue("--rail-width" to "@media (min-width: 48rem) and (max-width: 99.999rem)").value.value.toInt(), PanelWidthGeometry.defaultWidth(rail, ThemeFamily.Tactile, 1280))
        assertEquals(media.getValue("--rail-width" to "@media (min-width: 90rem)").value.value.toInt(), PanelWidthGeometry.defaultWidth(rail, ThemeFamily.Tactile, 1600))
        assertEquals(media.getValue("--inspector-width" to "@media (min-width: 90rem)").value.value.toInt(), PanelWidthGeometry.defaultWidth(inspector, ThemeFamily.Tactile, 1600))
    }

    /** A stored width renders through the CSS clamp(); nothing stored is the default; 0 never zeroes a column. */
    @Test fun effectiveWidthClampsStoredAndFallsBackToTheDefault() {
        assertEquals(264, PanelWidthGeometry.effectiveWidth(rail, null, ThemeFamily.Precision, 1280))
        assertEquals(360, PanelWidthGeometry.effectiveWidth(rail, 360, ThemeFamily.Precision, 1280))
        // A width stored on a wide display re-clamps on a narrower one (panel-widths.mjs:13-16).
        assertEquals(360, PanelWidthGeometry.effectiveWidth(rail, 470, ThemeFamily.Precision, 900))
        assertEquals(224, PanelWidthGeometry.effectiveWidth(rail, 0, ThemeFamily.Precision, 1280))
        assertEquals(448, PanelWidthGeometry.effectiveWidth(inspector, 8192, ThemeFamily.Studio, 1600))
    }

    /** The three fields round-trip through the whole preference model, other fields untouched. */
    @Test fun panelPrefsRoundTripThroughThePreferenceModel() {
        val base = TetherPreferences(showThinking = true)
        val panels = PanelPrefs(sidebarWidth = 312, inspectorWidth = null, sidebarCollapsed = true)
        val written = panels.applyTo(base)
        assertEquals(true, written.showThinking)
        assertEquals(panels, PanelPrefs.from(written))
        assertEquals(PanelPrefs(sidebarWidth = 312, inspectorWidth = 300, sidebarCollapsed = true), panels.withWidth(inspector, 300))
        assertEquals(PanelPrefs(sidebarWidth = null, sidebarCollapsed = true), panels.withWidth(rail, null))
    }
}
