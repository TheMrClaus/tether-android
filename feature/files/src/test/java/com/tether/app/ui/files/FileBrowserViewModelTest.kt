package com.tether.app.ui.files

import com.tether.app.client.FilesResult
import com.tether.app.ui.files.FilesFixtures.ROOT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
}
