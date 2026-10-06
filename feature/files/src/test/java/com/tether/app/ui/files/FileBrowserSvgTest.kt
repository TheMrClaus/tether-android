package com.tether.app.ui.files

import com.tether.app.client.FilesResult
import com.tether.app.ui.files.FilesFixtures.ROOT
import com.tether.app.ui.files.FilesFixtures.file
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit on purpose: a state machine that writes Compose state in a Robolectric class (without a compose
 * rule) left FileLifecycleTest's later recompositions stalled (L1 fix round). The SVG itself is opaque to the state,
 * so any ParsedSvg stands in; real parsing is SvgImagesTest.
 *
 * ta-1u4 in the browser's state: an SVG loads through the platform, has no byte cap, and fails with the image copy. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserSvgTest {
    private val files = FakeFiles().apply {
        listings[ROOT] = FilesResult.Ok(FilesFixtures.listing(entries = listOf(FilesFixtures.docs, file("logo.svg", 90))))
    }
    private val platform = FakePlatform()

    private fun TestScope.browser(): FileBrowserState =
        FileBrowserState(files, platform, CoroutineScope(StandardTestDispatcher(testScheduler))).apply {
            cwd = ROOT
            sessionName = FilesFixtures.SESSION
        }

    // --- svg ----------------------------------------------------------------------------------

    private val logo = file("logo.svg", 90)

    @Test fun anSvgLoadsThroughThePlatformAndShows() = runTest {
        platform.svg = SvgLoad.Ok(fakeSvg())
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(logo)
        assertTrue(s.previewLoading)
        advanceUntilIdle()
        assertEquals("loadSvg ${logo.path}", platform.calls.last { it.startsWith("loadSvg") })
        assertNotNull(s.svg)
        assertFalse(s.previewLoading)
        assertFalse("not a bitmap load", platform.calls.any { it.startsWith("loadImage") })
    }

    @Test fun anSvgHasNoByteCap() = runTest {
        platform.svg = SvgLoad.Ok(fakeSvg())
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(file("huge.svg", 5L * 1024 * 1024 * 1024))
        advanceUntilIdle()
        assertNotNull(s.svg)
        assertEquals("", s.previewError)
    }

    @Test fun anSvgThatCannotBeDisplayedUsesTheImageCopy() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(logo)
        advanceUntilIdle()
        assertEquals("This image could not be displayed.", s.previewError)
        assertNull(s.svg)
    }

    @Test fun aDrawFailureOfTheShownSvgUsesTheImageCopyAndAStaleOneSaysNothing() = runTest {
        val parsed = fakeSvg()
        platform.svg = SvgLoad.Ok(parsed)
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(logo)
        advanceUntilIdle()
        s.svgDrawFailed(fakeSvg())
        assertEquals("a different document is not the one on screen", "", s.previewError)
        s.svgDrawFailed(parsed)
        assertEquals("This image could not be displayed.", s.previewError)
    }

    @Test fun anSvgSelectionSupersededWhileLoadingNeverLands() = runTest {
        platform.svg = SvgLoad.Ok(fakeSvg())
        val gate = CompletableDeferred<Unit>()
        platform.svgGate = gate
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(logo)
        advanceUntilIdle()
        s.clearSelection()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(s.svg)
        assertNull(s.selected)
    }

    private fun fakeSvg(): ParsedSvg {
        val ctor = com.caverock.androidsvg.SVG::class.java.getDeclaredConstructor().apply { isAccessible = true }
        return ParsedSvg(ctor.newInstance(), SvgIntrinsic(), null)
    }
}
