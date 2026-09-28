package com.tether.app.ui.files

import com.tether.app.client.FilesResult
import com.tether.app.client.WorkspaceFiles
import com.tether.app.ui.files.FilesFixtures.README_TEXT
import com.tether.app.ui.files.FilesFixtures.ROOT
import com.tether.app.ui.files.FilesFixtures.listing
import com.tether.app.ui.files.FilesFixtures.readme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser's state machine against workspace-file-browser.tsx: what each operation sends,
 * in what order, and what the operator sees after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserStateTest {
    private val files = FakeFiles().apply { listings[ROOT] = FilesResult.Ok(listing()) }
    private val platform = FakePlatform()

    // Not backgroundScope: advanceUntilIdle() leaves background work alone.
    private fun TestScope.browser(): FileBrowserState = FileBrowserState(files, platform, CoroutineScope(StandardTestDispatcher(testScheduler))).apply {
        cwd = ROOT
        sessionName = FilesFixtures.SESSION
    }

    @Test fun openListsTheSessionCwdAndSweepsTheCache() = runTest {
        val s = browser()
        s.open()
        assertTrue(s.isOpen)
        assertTrue("Reading workspace…", s.loading)
        advanceUntilIdle()
        assertEquals(listOf("list $ROOT"), files.calls)
        assertFalse(s.loading)
        assertEquals(listOf("docs", "src", "package.json", "README.md"), s.listing!!.entries.map { it.name })
        assertEquals("by age only: a copy another app still reads stays", listOf("sweep Expired"), platform.calls)
    }

    @Test fun withoutASessionCwdNothingOpens() = runTest {
        val s = browser().apply { cwd = "" }
        s.open()
        advanceUntilIdle()
        assertFalse(s.isOpen)
        assertTrue(files.calls.isEmpty())
    }

    @Test fun openingAFilePathListsItsParentThenSelectsIt() = runTest {
        files.texts[readme.path] = FilesResult.Ok(README_TEXT)
        val s = browser()
        s.open(readme.path)
        advanceUntilIdle()
        assertEquals(listOf("list ${readme.path}", "list $ROOT", "readText ${readme.path} 65"), files.calls)
        assertEquals(readme, s.selected)
        assertEquals(README_TEXT, s.text)
    }

    @Test fun aSupersededListingNeverLands() = runTest {
        val gate = CompletableDeferred<Unit>()
        files.gates["list"] = gate
        files.listings["/other"] = FilesResult.Ok(listing("/other", emptyList()))
        val s = browser()
        s.open() // held at the gate
        advanceUntilIdle()
        s.loadDirectory("/other")
        advanceUntilIdle()
        assertEquals("/other", s.listing!!.current)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("the first (older) listing is dropped", "/other", s.listing!!.current)
    }

    @Test fun aListingThatLandsAsItIsCancelledIsStillDropped() = runTest {
        // The response is already in hand when the operator opens another folder.
        files.gatesIgnoreCancellation = true
        val gate = CompletableDeferred<Unit>()
        files.gates["list"] = gate
        files.listings["/other"] = FilesResult.Ok(listing("/other", emptyList()))
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.loadDirectory("/other")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("/other", s.listing!!.current)
        assertEquals(0, s.listing!!.entries.size)
    }

    /**
     * ta-g04: the browser's scope is viewModelScope (Dispatchers.Main.immediate), so a job started
     * from the main thread runs at once, and a result already in hand (a fast server, a preempted
     * main thread) never suspends it: the job finishes inside launch, before launch returns. Each
     * of these used to be taken for superseded and dropped, leaving "Reading workspace…" for good.
     */
    @Test fun aResultThatLandsBeforeItsLaunchReturnsIsKept() = runTest {
        files.listings["/other"] = FilesResult.Ok(listing("/other", listOf(readme)))
        files.texts[readme.path] = FilesResult.Ok(README_TEXT)
        val s = FileBrowserState(files, platform, CoroutineScope(UnconfinedTestDispatcher(testScheduler))).apply { cwd = ROOT }
        s.open()
        s.loadDirectory("/other")
        assertFalse("the second listing lands", s.loading)
        assertEquals("/other", s.listing!!.current)
        s.selectFile(FilesFixtures.file("a.txt", 5))
        s.selectFile(readme)
        assertFalse("the second preview lands", s.previewLoading)
        assertEquals(README_TEXT, s.text)
        s.openDestPicker(readme, DestinationMode.Copy)
        s.loadDestDirectory(ROOT)
        assertFalse("the second picker listing lands", s.destPicker!!.loading)
        assertEquals(ROOT, s.destPicker!!.listing!!.current)
    }

    @Test fun aFailedListingShowsTheServersCopyAndTryAgainRetriesThatPath() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.loadDirectory("/gone")
        advanceUntilIdle()
        assertEquals("That folder is not available.", s.error)
        s.retry()
        advanceUntilIdle()
        assertEquals("list /gone", files.calls.last())
    }

    @Test fun selectingATextFileFetchesItsPreviewOnce() = runTest {
        files.texts[readme.path] = FilesResult.Ok(README_TEXT)
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.activate(readme)
        assertTrue(s.previewLoading)
        advanceUntilIdle()
        assertEquals(README_TEXT, s.text)
        assertFalse(s.previewLoading)
        assertEquals("readText ${readme.path} 65", files.calls.last())
    }

    @Test fun anOversizedTextFileAndAnUnsupportedFileFetchNothing() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        val before = files.calls.size
        for (name in listOf("huge.log", "archive.zip", "diagram.svg", "clip.mp4")) {
            val size = if (name == "huge.log") WorkspaceFiles.MAX_TEXT_PREVIEW_BYTES + 1 else 10
            s.selectFile(FilesFixtures.file(name, size))
            advanceUntilIdle() // each one settles before the next replaces it
        }
        assertEquals(before, files.calls.size)
        assertTrue(platform.calls.none { it.startsWith("loadImage") })
    }

    @Test fun anImageIsLoadedThroughThePlatformAndAFailureUsesTheWebsCopy() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(FilesFixtures.file("shot.png", 1234))
        advanceUntilIdle()
        assertEquals("loadImage $ROOT/shot.png", platform.calls.last())
        assertEquals("This image could not be displayed.", s.previewError)
        platform.image = ImageLoad.TooLarge
        s.selectFile(FilesFixtures.file("big.jpg", 99))
        advanceUntilIdle()
        assertTrue(s.imageTooLarge)
        assertEquals("", s.previewError)
    }

    @Test fun openingAFolderCancelsAPendingPreview() = runTest {
        platform.imageGate = CompletableDeferred()
        platform.image = ImageLoad.TooLarge
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(FilesFixtures.file("shot.png", 1))
        advanceUntilIdle()
        files.listings["$ROOT/src"] = FilesResult.Ok(listing("$ROOT/src", emptyList()))
        s.activate(FilesFixtures.src)
        platform.imageGate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(s.selected)
        assertFalse(s.imageTooLarge)
    }

    @Test fun newFolderPostsTheTrimmedNameInTheCurrentFolderAndReloads() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.openNamePrompt(NamePromptMode.NewFolder)
        s.updateNamePrompt("  notes  ")
        s.submitNamePrompt()
        advanceUntilIdle()
        assertEquals(listOf("list $ROOT", "mkdir $ROOT notes", "list $ROOT"), files.calls)
        assertNull(s.namePrompt)
    }

    @Test fun aRejectedNameKeepsThePromptOpenWithTheServersCopy() = runTest {
        files.failures["touch"] = FilesResult.Failed("Name must be a single name (no path separators, no leading dot).", 400)
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.openNamePrompt(NamePromptMode.NewFile)
        s.updateNamePrompt(".env")
        s.submitNamePrompt()
        advanceUntilIdle()
        assertNotNull(s.namePrompt)
        assertEquals("Name must be a single name (no path separators, no leading dot).", s.namePromptError)
        assertEquals("no reload after a refusal", listOf("list $ROOT", "touch $ROOT .env"), files.calls)
    }

    @Test fun aBlankNameSendsNothingAndTheNameIsCappedAt200() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.openNamePrompt(NamePromptMode.NewFolder)
        s.updateNamePrompt("   ")
        s.submitNamePrompt()
        s.updateNamePrompt("x".repeat(500))
        assertEquals(200, s.namePrompt!!.value.length)
        advanceUntilIdle()
        assertEquals(listOf("list $ROOT"), files.calls)
    }

    @Test fun renamePrefillsTheNameAndSendsTheEntrysPath() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.openItemActions(readme)
        s.pickAction { s.openNamePrompt(NamePromptMode.Rename, it) }
        assertNull(s.itemActions)
        assertEquals("README.md", s.namePrompt!!.value)
        s.updateNamePrompt("GUIDE.md")
        s.submitNamePrompt()
        advanceUntilIdle()
        assertTrue(files.calls.contains("rename ${readme.path} GUIDE.md"))
    }

    @Test fun deleteNeedsTheConfirmStepAndSendsOnceEvenOnADoubleTap() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.openItemActions(FilesFixtures.docs)
        s.pickAction(s::openDeleteConfirm)
        advanceUntilIdle()
        assertTrue("opening the confirmation deletes nothing", files.calls.none { it.startsWith("delete") })
        val gate = CompletableDeferred<Unit>()
        files.gates["delete"] = gate
        s.confirmDelete()
        advanceUntilIdle()
        s.confirmDelete() // a second tap while the first is in flight
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, files.calls.count { it == "delete ${FilesFixtures.docs.path}" })
        assertNull(s.deleteTarget)
        assertEquals("list $ROOT", files.calls.last())
    }

    @Test fun aFailedDeleteKeepsTheConfirmationWithTheReason() = runTest {
        files.failures["delete"] = FilesResult.Failed("That item is not available.", 404)
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.openDeleteConfirm(readme)
        s.confirmDelete()
        advanceUntilIdle()
        assertEquals(readme, s.deleteTarget)
        assertEquals("That item is not available.", s.deleteError)
    }

    @Test fun moveBrowsesFoldersThenSendsTheSourceAndTheChosenFolder() = runTest {
        files.listings["$ROOT/docs"] = FilesResult.Ok(listing("$ROOT/docs", emptyList()))
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.openDestPicker(readme, DestinationMode.Move)
        advanceUntilIdle()
        assertEquals(ROOT, s.destPicker!!.path)
        s.loadDestDirectory("$ROOT/docs")
        advanceUntilIdle()
        assertEquals("$ROOT/docs", s.destPicker!!.path)
        s.confirmDestPicker()
        advanceUntilIdle()
        assertTrue(files.calls.contains("move ${readme.path} $ROOT/docs"))
        assertNull(s.destPicker)
        assertEquals("list $ROOT", files.calls.last())
    }

    @Test fun copyReportsAServerRefusalInThePicker() = runTest {
        files.failures["copy"] = FilesResult.Failed("An item with that name already exists here.", 409)
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.openDestPicker(readme, DestinationMode.Copy)
        advanceUntilIdle()
        s.confirmDestPicker()
        advanceUntilIdle()
        assertEquals("copy ${readme.path} $ROOT", files.calls.last())
        assertEquals("An item with that name already exists here.", s.destPicker!!.error)
    }

    @Test fun uploadsGoOneByOneIntoTheCurrentFolderAndTheLastFailureStays() = runTest {
        files.failures["upload:b.bin"] = FilesResult.Failed("An item with that name already exists here.", 409)
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.upload(listOf(PickedUpload("a.png", bytesSource(byteArrayOf(1, 2))), PickedUpload("b.bin", bytesSource(byteArrayOf(3)))))
        advanceUntilIdle()
        assertEquals(listOf("list $ROOT", "upload $ROOT a.png", "upload $ROOT b.bin", "list $ROOT"), files.calls)
        assertEquals(listOf("a.png", "b.bin"), files.uploaded.map { it.first })
        assertEquals("An item with that name already exists here.", s.mutationError)
        assertNull(s.uploading)
    }

    @Test fun anUploadIsNamedLikeABrowsersFileNameAndAControlCharacterIsRefused() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.upload(
            listOf(
                PickedUpload("C:\\fakepath\\report.pdf", bytesSource(byteArrayOf(1))),
                PickedUpload("evil\u0000.txt", bytesSource(byteArrayOf(2))),
                PickedUpload(null, bytesSource(byteArrayOf(3))),
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf("list $ROOT", "upload $ROOT report.pdf", "upload $ROOT upload", "list $ROOT"), files.calls)
        assertEquals("the refused one says why, in the server's words", UploadNames.INVALID, s.mutationError)
    }

    @Test fun theIdentityIsTheServerWhileSignedInAndNothingOnceRefused() {
        assertEquals("https://a", FileBrowserViewModel.identity(true, com.tether.app.client.ConnectionState.Connected, "https://a"))
        assertEquals("a reconnect is still the same session", "https://a", FileBrowserViewModel.identity(true, com.tether.app.client.ConnectionState.Connecting, "https://a"))
        assertNull("signed out", FileBrowserViewModel.identity(false, com.tether.app.client.ConnectionState.Disconnected, "https://a"))
        assertNull("credential refused", FileBrowserViewModel.identity(true, com.tether.app.client.ConnectionState.AuthRequired, "https://a"))
    }

    @Test fun closeLetsGoOfThePreview() = runTest {
        files.texts[readme.path] = FilesResult.Ok(README_TEXT)
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(readme)
        advanceUntilIdle()
        assertEquals(README_TEXT, s.text)
        s.close()
        assertNull(s.text)
        assertNull(s.image)
        assertNull(s.selected)
    }

    @Test fun closeAbortsAndSweepsButKeepsFreshSharedCopies() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.openItemActions(readme)
        s.close()
        assertFalse(s.isOpen)
        assertNull(s.itemActions)
        assertEquals(listOf("discardUnclaimed", "sweep Expired"), platform.calls.takeLast(2))
    }

    @Test fun fullscreenTogglesAndAFolderLoadLeavesIt() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(FilesFixtures.packageJson)
        s.toggleFullscreen()
        assertTrue(s.previewFullscreen)
        s.loadDirectory(ROOT)
        assertFalse(s.previewFullscreen)
    }
}
