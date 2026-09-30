package com.tether.app.gallery

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.icons.ProviderLogos
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The gallery's contract: complete, switchable, and reachable in debug builds. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
class ComponentGalleryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun everyPrimitiveAndCatalogueHasASection() {
        assertEquals(
            listOf(
                "keys", "wells", "seams", "chips", "status-pills", "select", "dialog", "sheet", "expandable",
                "rocker", "indicators", "typography", "icons", "provider-logos", "launcher-icon",
            ),
            GallerySections.map { it.id },
        )
    }

    @Test fun iconGridHoldsAllWebGlyphsAndTheGoldensSplitItWithoutGaps() {
        assertEquals(136, IconNames.size)
        assertEquals(TetherIcons.byWebName.keys.toList(), IconNames)
        // icons-1..3 together are exactly IconNames, in order (asserted via the same subList bounds).
        assertEquals(setOf("icons-1", "icons-2", "icons-3"), GalleryGoldens.keys.filter { it.startsWith("icons") }.toSet())
    }

    @Test fun providerSectionCoversEveryMarkAndTheFallbacks() {
        assertTrue(GalleryProviders.containsAll(ProviderLogos.paths.keys))
        assertTrue(GalleryProviders.any { ProviderLogos.mark(it) == null })
    }

    @Test fun keySectionCoversEveryKeyClass() {
        val used = GalleryKeySets.flatMap { it.second }.toSet()
        assertEquals(com.tether.app.ui.components.KeyClass.entries.toSet(), used)
    }

    @Test fun switcherResolvesAllSixSkins() {
        rule.setContent { ComponentGallery(ThemeChoice(ThemeFamily.Precision, ThemeMode.Light)) }
        val expected = mapOf(
            (ThemeFamily.Tactile to ThemeMode.Light) to "tactile",
            (ThemeFamily.Tactile to ThemeMode.Dark) to "night",
            (ThemeFamily.Precision to ThemeMode.Light) to "precision",
            (ThemeFamily.Precision to ThemeMode.Dark) to "machine",
            (ThemeFamily.Studio to ThemeMode.Light) to "studio",
            (ThemeFamily.Studio to ThemeMode.Dark) to "studio-dark",
        )
        for ((pair, skin) in expected) {
            // A chip is announced by its label (contentDescription; the legend text is cleared).
            rule.onNodeWithContentDescription(pair.first.label).performClick()
            rule.onNodeWithContentDescription(pair.second.label).performClick()
            rule.onNodeWithTag(SkinLabelTag).assertTextEquals("skin: $skin")
        }
    }

    // Robolectric implements the int-flag PackageManager overloads, not the *Flags.of ones.
    @Suppress("DEPRECATION")
    @Test fun galleryActivityIsADebugLauncherEntry() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val component = ComponentName(context, GalleryActivity::class.java)
        val info = context.packageManager.getActivityInfo(component, 0)
        assertTrue(info.exported)
        val launchers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            0,
        )
        assertTrue(launchers.any { it.activityInfo.name == GalleryActivity::class.java.name })
    }

    @Test fun galleryActivityStarts() {
        Robolectric.buildActivity(GalleryActivity::class.java).setup().use { controller ->
            assertTrue(!controller.get().isFinishing)
        }
    }
}
