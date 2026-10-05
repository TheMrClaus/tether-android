package com.tether.app.ui.inspector

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tether.app.client.TetherClient
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.shell.ShellConsentClient
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.5: the Repository panel's draft keys (tether 90fbb9f repository-panel.tsx:27-28, 40-51;
 * dashboard.tsx:1460-1461): shown when the server's `metadataGenerationEnabled === true` and the
 * session is not read-only (the panel shows for them alone), the web's words, and a tap sends the
 * session's `metadata-draft-request` at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class InspectorDraftActionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val worktree = WorktreeInfo(path = "/w", branch = "feat", status = "active", mode = "branch-off")

    private fun settings(value: kotlinx.serialization.json.JsonElement?) = ServerMessage.ServerSettings(
        settings = JsonObject(if (value == null) emptyMap() else mapOf("metadataGenerationEnabled" to value)),
        envForced = emptyMap(),
        restartRequired = false,
        discovered = emptyList(),
        detected = JsonObject(emptyMap()),
    )

    @Test fun theSettingIsStrictlyTrue() {
        assertTrue(metadataGenerationEnabled(settings(JsonPrimitive(true))))
        assertFalse(metadataGenerationEnabled(settings(JsonPrimitive(false))))
        assertFalse(metadataGenerationEnabled(settings(JsonPrimitive("true"))))
        assertFalse(metadataGenerationEnabled(settings(JsonPrimitive(1))))
        assertFalse(metadataGenerationEnabled(settings(null)))
        assertFalse(metadataGenerationEnabled(null))
    }

    @Test fun theModelShowsTheKeysOnlyWhenOnAndWritable() {
        val bare = InspectorBoards.session()
        // repository-panel.tsx:28: nothing else to show and no keys = no panel.
        assertNull(InspectorBoards.model(bare).repository)
        val on = InspectorBoards.model(bare, metadataGenerationEnabled = true).repository
        assertNotNull(on)
        assertTrue(on!!.canDraft)
        assertNull(on.branch)
        assertNull(InspectorBoards.model(bare.copy(readOnly = true), metadataGenerationEnabled = true).repository)
        val branchOnly = InspectorBoards.model(InspectorBoards.session(worktree = worktree)).repository!!
        assertFalse(branchOnly.canDraft)
        assertFalse(InspectorBoards.model(InspectorBoards.session(worktree = worktree).copy(readOnly = true), metadataGenerationEnabled = true).repository!!.canDraft)
    }

    private class Recording(base: TetherClient = ShellConsentClient()) : TetherClient by base {
        val sent = mutableListOf<String>()
        val settings = MutableStateFlow<ServerMessage.ServerSettings?>(null)
        override val serverSettings: StateFlow<ServerMessage.ServerSettings?> get() = settings
        override fun requestMetadataDraft(draftKind: String, sessionId: String): Boolean { sent += "$draftKind:$sessionId"; return true }
    }

    private fun host(client: Recording) {
        val vm = TetherViewModel(client)
        val session = InspectorBoards.session(worktree = worktree)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { InspectorHost(vm, session, null) }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun theKeysFollowTheServerSettingAndSendAtOnce() {
        val client = Recording()
        host(client)
        rule.onNodeWithTag(InspectorTags.DraftActions, useUnmergedTree = true).assertDoesNotExist()

        client.settings.value = settings(JsonPrimitive(true))
        rule.waitForIdle()
        rule.onNodeWithText("Draft commit message", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("From staged changes", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Draft pull request", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Title and description to review", useUnmergedTree = true).assertExists()

        rule.onNodeWithTag(InspectorTags.DraftCommitMessage).performScrollTo().performClick()
        rule.onNodeWithTag(InspectorTags.DraftPullRequest).performScrollTo().performClick()
        rule.onNodeWithTag(InspectorTags.DraftCommitMessage).performScrollTo().performClick()
        rule.waitForIdle()
        // No confirmation and no busy state: each tap is one request, as on the web.
        assertEquals(listOf("commitMessage:s1", "pullRequest:s1", "commitMessage:s1"), client.sent)

        client.settings.value = settings(JsonPrimitive(false))
        rule.waitForIdle()
        rule.onNodeWithTag(InspectorTags.DraftActions, useUnmergedTree = true).assertDoesNotExist()
    }
}
