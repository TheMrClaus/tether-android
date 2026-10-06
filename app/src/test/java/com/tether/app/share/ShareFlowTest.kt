package com.tether.app.share

import com.tether.app.nav.runPrefsWrite

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.serverOrigin
import com.tether.app.nav.NavTestClient
import com.tether.app.nav.NavTestClient.Companion.LISTED
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.UiRoot
import com.tether.app.ui.chat.SharedFileSource
import com.tether.app.ui.share.ShareTargetTags
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T11.2 in the shell: a share waits through sign-in, then the chooser places it in the composer the
 * user picks (an existing session's, or the new-session draft), the file staged through that
 * composer's own intake and the text in its draft. Nothing is ever sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
class ShareFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val client = NavTestClient(configured = false)
    private val dir: File = Files.createTempDirectory("share").toFile()

    @Before
    fun setUp() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        runPrefsWrite {
            com.tether.app.ui.prefs.UiPrefs(ApplicationProvider.getApplicationContext<Context>()).setLastView(serverOrigin(NavTestClient.PAIRED), "sessions")
        }
        clearInbox()
        rule.setContent { UiRoot(client = client) }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        clearInbox()
        dir.deleteRecursively()
        assertEquals("a share never sends", emptyList<String>(), client.stateChanges.toList())
    }

    private fun clearInbox() {
        ShareInbox.pending.value?.let { ShareInbox.discard(it.id) }
    }

    private val vm: TetherViewModel get() = ViewModelProvider(rule.activity)[TetherViewModel::class.java]

    private fun share(text: String? = "shared text"): PendingShare {
        val file = File(dir, "item-0").apply { writeText("file body") }
        val share = PendingShare(ShareInbox.nextId(), text, listOf(SharedFileSource("notes.txt", file.length(), "text/plain", file)), dir)
        rule.runOnUiThread { ShareInbox.offer(share) }
        rule.waitForIdle()
        return share
    }

    private fun signIn() {
        rule.runOnUiThread { client.configuredFlow.value = true }
        rule.waitForIdle()
    }

    @Test
    fun aShareWaitsThroughSignInThenGoesToTheChosenSessionsComposer() {
        share()
        rule.onNodeWithTag(ShareTargetTags.DIALOG, useUnmergedTree = true).assertDoesNotExist()
        signIn()
        rule.onNodeWithTag(ShareTargetTags.DIALOG, useUnmergedTree = true).assertExists()

        rule.onNodeWithTag(ShareTargetTags.session(LISTED)).performClick()
        rule.waitUntil(5_000) { vm.stagedAttachments.items(vm.attachmentOrigin(), LISTED).isNotEmpty() }
        assertEquals(LISTED, vm.selectedSessionId.value)
        val staged = vm.stagedAttachments.items(vm.attachmentOrigin(), LISTED)
        assertEquals(listOf("notes.txt"), staged.map { it.attachment.name })
        rule.waitUntil(5_000) { vm.drafts.value[LISTED] == "shared text" }
        rule.onNodeWithTag(ShareTargetTags.DIALOG, useUnmergedTree = true).assertDoesNotExist()
        assertNull(ShareInbox.pending.value)
        rule.waitUntil(5_000) { !dir.exists() }
        assertFalse(vm.draftOpen.value)
    }

    @Test
    fun newSessionOpensTheDraftWithTheFileStagedAndTheText() {
        signIn()
        share()
        rule.onNodeWithTag(ShareTargetTags.NEW_SESSION).performClick()
        rule.waitUntil(5_000) { vm.draftComposer.state.value.staged.isNotEmpty() }
        assertTrue(vm.draftOpen.value)
        assertEquals("shared text", vm.draftComposer.state.value.text)
        assertEquals(listOf("notes.txt"), vm.draftComposer.state.value.staged.map { it.attachment.name })
    }

    @Test
    fun cancelDropsTheShare() {
        signIn()
        share()
        rule.onNodeWithTag(ShareTargetTags.CANCEL).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ShareTargetTags.DIALOG, useUnmergedTree = true).assertDoesNotExist()
        assertNull(ShareInbox.pending.value)
        assertFalse(dir.exists())
        assertFalse(vm.draftOpen.value)
        assertTrue(vm.stagedAttachments.current.value == null)
    }
}
