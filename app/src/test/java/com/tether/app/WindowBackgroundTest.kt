package com.tether.app

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.theme.GeneratedTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T15.5 r2: the window painted before Compose (and the Android 12+ splash, which takes the same
 * colour) is Studio's --mineral in the device's lighting, never a retired skin's (OVERVIEW_STUDIO_PLAN
 * §3: pre-paint fallbacks are Studio's). Resolved through Theme.Tether, as the system does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowBackgroundTest {

    private fun resolved(attr: Int): Int {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = ContextThemeWrapper(app, R.style.Theme_Tether)
        val a = themed.obtainStyledAttributes(intArrayOf(attr))
        try {
            val drawable = a.getDrawable(0)
            assertTrue("$attr is a plain colour", drawable is ColorDrawable)
            return (drawable as ColorDrawable).color
        } finally {
            a.recycle()
        }
    }

    private fun argb(c: androidx.compose.ui.graphics.Color): Int = android.graphics.Color.argb(
        (c.alpha * 255).toInt(), (c.red * 255 + 0.5f).toInt(), (c.green * 255 + 0.5f).toInt(), (c.blue * 255 + 0.5f).toInt(),
    )

    @Test @Config(qualifiers = "notnight")
    fun dayWindowIsStudioLightMineral() {
        assertEquals(0xFFF4F6FA.toInt(), resolved(android.R.attr.windowBackground))
        assertEquals(argb(GeneratedTokens.Studio.mineral), resolved(android.R.attr.windowBackground))
        assertEquals(0xFFF4F6FA.toInt(), resolved(android.R.attr.windowSplashScreenBackground))
    }

    @Test @Config(qualifiers = "night")
    fun nightWindowIsStudioDarkMineral() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(Configuration.UI_MODE_NIGHT_YES, app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(0xFF101725.toInt(), resolved(android.R.attr.windowBackground))
        assertEquals(argb(GeneratedTokens.StudioDark.mineral), resolved(android.R.attr.windowBackground))
        assertEquals(0xFF101725.toInt(), resolved(android.R.attr.windowSplashScreenBackground))
    }

    /** The retired skins' --mineral and --graphite (machine, night, precision, tactile), from the pre-T15.5 export. */
    private val retired = setOf(
        0xFF0B0F10, 0xFF111517, 0xFF101211, 0xFF181A19, 0xFFE0E5E5, 0xFFF2F4F3, 0xFFD2D4CE, 0xFFE6E7E2,
    ).map { it.toInt() }.toSet()

    @Test @Config(qualifiers = "notnight")
    fun noRetiredColourByDay() {
        assertTrue(resolved(android.R.attr.windowBackground) !in retired)
        assertTrue(resolved(android.R.attr.windowSplashScreenBackground) !in retired)
    }

    @Test @Config(qualifiers = "night")
    fun noRetiredColourByNight() {
        assertTrue(resolved(android.R.attr.windowBackground) !in retired)
        assertTrue(resolved(android.R.attr.windowSplashScreenBackground) !in retired)
    }
}
