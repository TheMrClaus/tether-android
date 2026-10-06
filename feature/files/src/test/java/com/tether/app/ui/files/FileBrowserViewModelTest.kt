package com.tether.app.ui.files

import com.tether.app.client.FilesResult
import com.tether.app.ui.files.FilesFixtures.ROOT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ta-u2n: the browser's owner ends a session on a change of signed-in identity, and only then. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModelTest {
    private val main = StandardTestDispatcher()
    private val files = FakeFiles().apply { listings[ROOT] = FilesResult.Ok(FilesFixtures.listing()) }
    private val platform = FakePlatform()
    private val identity = MutableStateFlow<String?>("https://server-a")

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun theFirstIdentityBindsWithoutAnyTeardown() = runTest(main) {
        val vm = FileBrowserViewModel(files, platform, identity)
        val state = vm.state
        advanceUntilIdle()
        assertSame(state, vm.state)
        assertTrue(platform.calls.none { it.startsWith("sweep") })
    }

    @Test fun signOutCancelsTheOldSessionsJobsAndSweepsEverything() = runTest(main) {
        val vm = FileBrowserViewModel(files, platform, identity)
        advanceUntilIdle()
        val old = vm.state.apply { cwd = ROOT }
        old.open()
        advanceUntilIdle()
        // An upload in flight when the operator signs out.
        val gate = CompletableDeferred<Unit>()
        files.gates["upload"] = gate
        old.upload(listOf(PickedUpload("a.txt", bytesSource(byteArrayOf(1)))))
        advanceUntilIdle()
        identity.value = null
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("the signed-out session's upload never completes", files.uploaded.isEmpty())
        assertEquals("sweep All", platform.calls.last())
        assertNotSame(old, vm.state)
        assertFalse(vm.state.isOpen)
    }

    @Test fun anotherServerAlsoEndsTheSession() = runTest(main) {
        val vm = FileBrowserViewModel(files, platform, identity)
        advanceUntilIdle()
        val old = vm.state
        identity.value = "https://server-b"
        advanceUntilIdle()
        assertNotSame(old, vm.state)
        assertTrue(platform.calls.contains("sweep All"))
    }

    /** ta-1u4: the selected video's player does not outlive the session (sign-out) or the server (a switch). */
    @Test fun signOutAndAServerSwitchReleaseThePlayer() = runTest(main) {
        val clip = FilesFixtures.file("clip.mp4", 700)
        val vm = FileBrowserViewModel(files, platform, identity)
        advanceUntilIdle()
        vm.state.apply { cwd = ROOT }.open()
        advanceUntilIdle()
        vm.state.selectFile(clip)
        val first = platform.players.single()
        identity.value = null
        advanceUntilIdle()
        assertEquals("sign-out", 1, first.releases)
        // Signed in again, to another server: a fresh browser, a fresh video, released by the switch.
        identity.value = "https://server-b"
        advanceUntilIdle()
        vm.state.apply { cwd = ROOT }.open()
        advanceUntilIdle()
        vm.state.selectFile(clip)
        val second = platform.players.last()
        identity.value = "https://server-c"
        advanceUntilIdle()
        assertEquals("server switch", 1, second.releases)
        assertEquals(1, first.releases)
    }

    /** ta-3pf: the sign-in generation is part of the identity, so a same-server sign-out and sign-in is a new session. */
    @Test fun aSameServerSignOutAndSignInIsANewSessionThroughTheGeneration() = runTest(main) {
        val generation = MutableStateFlow(1L)
        val vm = FileBrowserViewModel(files, platform, combine(identity, generation, FileBrowserViewModel::withGeneration))
        advanceUntilIdle()
        val first = vm.state
        assertTrue(platform.calls.none { it.startsWith("sweep") })
        generation.value = 2
        advanceUntilIdle()
        assertNotSame("the same server, a new sign-in", first, vm.state)
        assertEquals("sweep All", platform.calls.last())
        // The same generation again (a reconnect) is the same session.
        val second = vm.state
        advanceUntilIdle()
        assertSame(second, vm.state)
    }

    @Test fun theIdentityWithAGenerationIsNullWhenSignedOutAndDiffersPerGeneration() {
        assertEquals(null, FileBrowserViewModel.withGeneration(null, 7))
        assertEquals("https://a#3", FileBrowserViewModel.withGeneration("https://a", 3))
        assertFalse(FileBrowserViewModel.withGeneration("https://a", 3) == FileBrowserViewModel.withGeneration("https://a", 4))
    }
}
