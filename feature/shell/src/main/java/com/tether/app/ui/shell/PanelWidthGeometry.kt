package com.tether.app.ui.shell

import com.tether.app.protocol.helpers.PanelWidths
import com.tether.app.protocol.helpers.get
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.theme.ThemeFamily

/**
 * The two draggable desktop columns (issue #186): the session rail and the inspector
 * (lib/panel-widths.mjs `PanelKind`). [id] is the web's kind string.
 */
enum class PanelKind(val id: String, val label: String) {
    /** `.session-sidebar`; its edge is the right side, so dragging right widens it. */
    Rail("rail", "Resize session sidebar"),

    /** `.inspector`; its edge is the left side, so dragging right narrows it. */
    Inspector("inspector", "Resize session details"),
}

/** `panelWidthBounds()` result: the allowed width range in px (CSS px = dp here). */
data class PanelBounds(val min: Int, val max: Int)

/**
 * Typed entry points over [PanelWidths], the conformance-tested port of lib/panel-widths.mjs
 * (T2.2), plus the per-theme / per-breakpoint DEFAULT widths the web keeps in CSS custom
 * properties. Everything is in CSS px, which the app draws 1:1 as dp. The root font size is
 * 16px: the app sizes rem-based layout at 16dp (as every other shell surface does) and only
 * text follows the font scale.
 */
object PanelWidthGeometry {
    /** `PANEL_WIDTH_STEP_PX` / `PANEL_WIDTH_LARGE_STEP_PX` (panel-widths.mjs:42-43). */
    const val STEP_PX: Int = PanelWidths.PANEL_WIDTH_STEP_PX
    const val LARGE_STEP_PX: Int = PanelWidths.PANEL_WIDTH_LARGE_STEP_PX

    private const val ROOT_FONT_SIZE_PX = 16.0

    private fun env(viewportWidth: Int): JsValue =
        JsObj.of("viewportWidth" to js(viewportWidth.toDouble()), "rootFontSize" to js(ROOT_FONT_SIZE_PX))

    /** panel-widths.mjs:81-87: `[minRem floor, min(maxRem, maxVw) ceiling]`, the floor winning. */
    fun bounds(kind: PanelKind, viewportWidth: Int): PanelBounds {
        val b: JsValue = PanelWidths.panelWidthBounds(JsStr(kind.id), env(viewportWidth))
        return PanelBounds((b["min"] as JsNum).value.toInt(), (b["max"] as JsNum).value.toInt())
    }

    /** panel-widths.mjs:91-95: rounded into the bounds; a non-finite width clamps to the floor. */
    fun clamp(kind: PanelKind, px: Double, viewportWidth: Int): Int =
        PanelWidths.clampPanelWidth(JsStr(kind.id), JsNum(px), env(viewportWidth)).toInt()

    /** panel-widths.mjs:113-117: the inspector's edge is its left side, so the delta is inverted. */
    fun fromDrag(kind: PanelKind, startWidth: Double, deltaX: Double, viewportWidth: Int): Int =
        PanelWidths.panelWidthFromDrag(JsStr(kind.id), startWidth, deltaX, env(viewportWidth)).toInt()

    /** The web's arrow keys on the separator (panel-widths.mjs:124-133). */
    enum class Arrow(val key: String) { Left("ArrowLeft"), Right("ArrowRight") }

    /** The signed width delta for an arrow (the arrow moves the EDGE: Right narrows the inspector). */
    fun keyDelta(kind: PanelKind, arrow: Arrow, shift: Boolean): Int =
        PanelWidths.panelWidthKeyDelta(JsStr(kind.id), JsStr(arrow.key), shift)!!.toInt()

    /** panel-widths.mjs:66-74: a stored width read fail-soft (null = the theme default). */
    fun parseStored(raw: Any?): Int? {
        val js: JsValue = when (raw) {
            is Number -> JsNum(raw.toDouble())
            is String -> JsStr(raw)
            else -> return null
        }
        return PanelWidths.parseStoredPanelWidth(js)?.toInt()
    }

    /**
     * The active theme's width for a column with nothing stored — the `--rail-width` /
     * `--inspector-width` cascade at [viewportWidth]:
     *
     * - instrument skins: rail 18rem (globals.css:70), 20rem from 90rem (11727), and 16.5rem for
     *   48rem ≤ w < 100rem (11856, later in the file, so it wins between 90 and 100rem);
     *   inspector 16.5rem (:71), 17rem from 90rem (11727).
     * - Studio: rail 17rem and inspector 18rem at every width (studio.css:48-49 — the same
     *   (0,1,0) specificity as those `:root` rules, and studio.css loads after globals.css).
     */
    fun defaultWidth(kind: PanelKind, family: ThemeFamily, viewportWidth: Int): Int {
        if (family == ThemeFamily.Studio) return if (kind == PanelKind.Rail) 272 else 288
        return when (kind) {
            PanelKind.Rail -> when {
                viewportWidth in 768 until 1600 -> 264
                viewportWidth >= 1440 -> 320
                else -> 288
            }
            PanelKind.Inspector -> if (viewportWidth >= 1440) 272 else 264
        }
    }

    /**
     * The rendered width: the stored width through the CSS `clamp()` that `panelWidthCss` emits
     * (panel-widths.mjs:101-106, re-clamped against the live viewport), else the theme default.
     */
    fun effectiveWidth(kind: PanelKind, stored: Int?, family: ThemeFamily, viewportWidth: Int): Int {
        val parsed = stored?.let { parseStored(it) } ?: return defaultWidth(kind, family, viewportWidth)
        return clamp(kind, parsed.toDouble(), viewportWidth)
    }
}

/**
 * The desktop layout's persisted per-device state: the dragged widths (null = theme default)
 * and the collapsed rail — the web preferences `sidebarWidth`, `inspectorWidth` and
 * `sidebarCollapsed` (hooks/use-preferences.ts:164-171; stored by [com.tether.app.ui.prefs.UiPrefs]
 * under the same model).
 */
data class PanelPrefs(
    val sidebarWidth: Int? = null,
    val inspectorWidth: Int? = null,
    val sidebarCollapsed: Boolean = false,
) {
    fun width(kind: PanelKind): Int? = if (kind == PanelKind.Rail) sidebarWidth else inspectorWidth

    fun withWidth(kind: PanelKind, width: Int?): PanelPrefs =
        if (kind == PanelKind.Rail) copy(sidebarWidth = width) else copy(inspectorWidth = width)

    /** Writes these fields into the whole preference model (the web's `{ ...preferences, … }`). */
    fun applyTo(prefs: TetherPreferences): TetherPreferences =
        prefs.copy(sidebarWidth = sidebarWidth, inspectorWidth = inspectorWidth, sidebarCollapsed = sidebarCollapsed)

    companion object {
        fun from(prefs: TetherPreferences): PanelPrefs =
            PanelPrefs(prefs.sidebarWidth, prefs.inspectorWidth, prefs.sidebarCollapsed)
    }
}

/** The web's viewport breakpoints the desktop layout switches on (CSS px = dp, unscaled). */
object ExpandedBreakpoints {
    /** `(min-width: 64rem)`: the stage reserves the timeline rail's left gutter (globals.css 11912). */
    const val STAGE_GUTTER = 1024

    /** `(min-width: 80rem)`: the topbar tool keys print their words (globals.css 11844). */
    const val TOOL_LABELS = 1280

    /** `(max-width: 74rem)` hides Studio's "Workspace" caption (studio.css 433), so it needs more. */
    const val STUDIO_CAPTION_ABOVE = 1184

    /**
     * `(min-width: 100rem)`: the inspector becomes the third column and the telemetry sheet goes
     * away (globals.css 3849, 4139); below it the sheet floats beside the conversation.
     */
    const val INSPECTOR_COLUMN = 1600
}
