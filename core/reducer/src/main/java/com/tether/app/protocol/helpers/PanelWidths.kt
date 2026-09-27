package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import kotlin.math.max
import kotlin.math.min

/** T2.2: faithful port of lib/panel-widths.mjs — the draggable column geometry (pure). */
object PanelWidths {

    data class Limits(val minRem: Double, val maxRem: Double, val maxVw: Double)

    // lib/panel-widths.mjs:30
    val PANEL_WIDTH_LIMITS: Map<String, Limits> = linkedMapOf(
        "rail" to Limits(minRem = 14.0, maxRem = 30.0, maxVw = 40.0),
        "inspector" to Limits(minRem = 13.0, maxRem = 28.0, maxVw = 35.0),
    )

    // lib/panel-widths.mjs:36
    val PANEL_WIDTH_VARS: Map<String, String> = linkedMapOf("rail" to "--rail-width", "inspector" to "--inspector-width")

    // lib/panel-widths.mjs:42
    const val PANEL_WIDTH_STEP_PX = 16
    const val PANEL_WIDTH_LARGE_STEP_PX = 64

    private const val STORED_WIDTH_CEILING_PX = 8192.0
    private const val DEFAULT_ROOT_FONT_SIZE_PX = 16.0

    // lib/panel-widths.mjs:53 — `PANEL_WIDTH_LIMITS[kind]`: the kind is ToString-coerced.
    private fun limitsFor(kind: JsValue?): Limits =
        PANEL_WIDTH_LIMITS[jsToString(kind)] ?: throw JsError("TypeError", "unknown panel kind: ${jsToString(kind)}")

    // lib/panel-widths.mjs:66 — a rounded, capped, non-negative width, or null (the theme default).
    fun parseStoredPanelWidth(raw: JsValue?): Double? {
        var value: Double
        when (raw) {
            is JsStr -> {
                if (com.tether.app.protocol.fold.jsTrim(raw.value).isEmpty()) return null
                value = jsNumberFromString(raw.value)
            }
            is JsNum -> value = raw.value
            else -> return null
        }
        if (!value.isFinite()) return null
        return min(STORED_WIDTH_CEILING_PX, max(0.0, jsRound(value))) // Math.max(0, -0) is +0, as here
    }

    /** Env `{ rootFontSize?, viewportWidth? }`; a missing or non-positive reading takes the default. */
    private fun positive(env: JsValue?, key: String): Double? {
        // `env = {}` only covers undefined; `null.rootFontSize` throws on the web.
        if (env === JsNull) throw JsError("TypeError", "Cannot read properties of null (reading '$key')")
        return (env[key] as? JsNum)?.value?.takeIf { it > 0 }
    }

    // lib/panel-widths.mjs:81
    fun panelWidthBounds(kind: JsValue?, env: JsValue? = null): JsObj {
        val (min, max) = bounds(kind, env)
        return JsObj.of("min" to js(min), "max" to js(max))
    }

    private fun bounds(kind: JsValue?, env: JsValue?): Pair<Double, Double> {
        val limits = limitsFor(kind)
        val rootFontSize = positive(env, "rootFontSize") ?: DEFAULT_ROOT_FONT_SIZE_PX
        val viewportWidth = positive(env, "viewportWidth") ?: Double.POSITIVE_INFINITY
        val lo = limits.minRem * rootFontSize
        val hi = max(lo, min(limits.maxRem * rootFontSize, (limits.maxVw * viewportWidth) / 100))
        return jsRound(lo) to jsRound(hi)
    }

    // lib/panel-widths.mjs:91 — junk clamps to the floor.
    fun clampPanelWidth(kind: JsValue?, px: JsValue?, env: JsValue? = null): Double {
        val (lo, hi) = bounds(kind, env)
        val value = (px as? JsNum)?.value
        if (value == null || !value.isFinite()) return lo
        return min(hi, max(lo, jsRound(value)))
    }

    // lib/panel-widths.mjs:101 — the inline CSS clamp(), or null for "no override".
    fun panelWidthCss(kind: JsValue?, px: JsValue?): String? {
        val limits = limitsFor(kind)
        val stored = parseStoredPanelWidth(px) ?: return null
        return "clamp(${n(limits.minRem)}rem, ${n(stored)}px, min(${n(limits.maxRem)}rem, ${n(limits.maxVw)}vw))"
    }

    // lib/panel-widths.mjs:113 — the inspector's edge is its left side, so the delta is inverted.
    fun panelWidthFromDrag(kind: JsValue?, startWidth: Double, deltaX: Double, env: JsValue? = null): Double {
        limitsFor(kind)
        val signed = if (kind.isStr("inspector")) -deltaX else deltaX
        return clampPanelWidth(kind, JsNum(startWidth + signed), env)
    }

    // lib/panel-widths.mjs:124 — the signed px delta for an arrow key, or null for any other key.
    fun panelWidthKeyDelta(kind: JsValue?, key: JsValue?, shiftKey: Boolean = false): Double? {
        limitsFor(kind)
        val step = if (shiftKey) PANEL_WIDTH_LARGE_STEP_PX else PANEL_WIDTH_STEP_PX
        val direction = when {
            key.isStr("ArrowRight") -> 1
            key.isStr("ArrowLeft") -> -1
            else -> return null
        }
        return ((if (kind.isStr("inspector")) -direction else direction) * step).toDouble()
    }

    private fun n(d: Double) = numberToString(d)
}
