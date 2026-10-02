package com.tether.app.ui.draft

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.IntSize
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.MainShell
import com.tether.app.ui.NEW_SESSION_PENDING_TAG
import com.tether.app.ui.NEW_SESSION_ROW_TAG
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-2uq (T8.1 slice 3): the Model chip and its browser over the real shell, view model and draft
 * engine, with a client that throttles `refresh-providers` as the real one does. Every tap is a
 * semantics action; every read after one waits on the model or the drawn screen (the ta-b72 lesson),
 * under the v2 compose rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ModelBrowserBehaviourTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val job = Job()
    private val prefs: UiPrefs by lazy {
        UiPrefs.on(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") })
    }
    private val client = DraftTestClient()
    private val vm by lazy { TetherViewModel(client) }
    private val composer get() = vm.draftComposer

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(412, 915)
    }

    @After fun closeStore() = runBlocking { job.cancel() }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun until(what: String = "condition", condition: () -> Boolean) = try {
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            condition()
        }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("timed out waiting for: $what", e)
    }

    private fun awaitTag(tag: String) = until("tag $tag") { exists(tag) }

    private fun awaitGone(tag: String) = until("no tag $tag") { !exists(tag) }

    private fun tap(tag: String) {
        awaitTag(tag)
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun typeInto(tag: String, text: String) {
        awaitTag(tag)
        rule.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(text)
        until("the field holds the text") { rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text == text }
    }

    private fun spoken(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    private fun enabled(tag: String) = runCatching { rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsEnabled() }.isSuccess

    private fun formKey() = (composer.state.value.form["key"] as? JsStr)?.value.orEmpty()

    private fun formModel() = (composer.state.value.form["model"] as? JsStr)?.value.orEmpty()

    private fun openBrowser() {
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Sheet)
        tap(ModelBrowserTags.Chip)
        awaitTag(ModelBrowserTags.Browser)
    }

    @Test
    fun theSearchCoversEveryModelAndAPickClosesTheBrowser() {
        openBrowser()
        until("the chip says nothing is picked") { spoken(ModelBrowserTags.Chip).contains("Select model") }
        awaitTag(ModelBrowserTags.SearchAll)
        typeInto(ModelBrowserTags.SearchAll, "Model 2")
        // Every provider's "Model 2", from one search.
        for (key in listOf("work", "personal", "claude", "codex")) awaitTag(ModelBrowserTags.row(key, "m2"))
        assertFalse(exists(ModelBrowserTags.row("claude", "m1")))
        assertTrue("a search result names its provider", spoken(ModelBrowserTags.row("codex", "m2")).contains("Codex"))
        typeInto(ModelBrowserTags.SearchAll, "zzz")
        until("no matches") { exists(ModelBrowserTags.Empty) && !exists(ModelBrowserTags.row("codex", "m2")) }
        typeInto(ModelBrowserTags.SearchAll, "model 2")
        tap(ModelBrowserTags.row("codex", "m2"))
        awaitGone(ModelBrowserTags.Browser)
        until("the pick set the row and the model") { formKey() == "codex" && formModel() == "m2" }
        until("the chip names it") { spoken(ModelBrowserTags.Chip).contains("Model 2") }
    }

    @Test
    fun aProviderViewSearchesItsOwnModelsAndBackReturnsToTheRows() {
        openBrowser()
        tap(NEW_SESSION_ROW_TAG + "work")
        for (m in listOf("m1", "m2", "m3")) awaitTag(ModelBrowserTags.row("work", m))
        assertFalse("another provider's models are not here", exists(ModelBrowserTags.row("claude", "m4")))
        typeInto(ModelBrowserTags.Search, "3")
        awaitGone(ModelBrowserTags.row("work", "m1"))
        awaitTag(ModelBrowserTags.row("work", "m3"))
        tap(ModelBrowserTags.Back)
        awaitTag(NEW_SESSION_ROW_TAG + "claude")
        until("the search was cleared") { rule.onNodeWithTag(ModelBrowserTags.SearchAll, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text == "" }
        // Reopened after a pick, the browser starts on the picked row (resolveInitialModelBrowserView).
        tap(NEW_SESSION_ROW_TAG + "personal")
        tap(ModelBrowserTags.row("personal", "m2"))
        awaitGone(ModelBrowserTags.Browser)
        tap(ModelBrowserTags.Chip)
        awaitTag(ModelBrowserTags.row("personal", "m2"))
        until("the pick is announced selected") {
            rule.onNodeWithTag(ModelBrowserTags.row("personal", "m2"), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true
        }
    }

    @Test
    fun thePickedRowAndModelRideTheCreate() {
        openBrowser()
        tap(NEW_SESSION_ROW_TAG + "work")
        tap(ModelBrowserTags.row("work", "m2"))
        awaitGone(ModelBrowserTags.Browser)
        until("picked") { formKey() == "work" && formModel() == "m2" }
        rule.onNodeWithTag(DraftComposerTags.Input).performTextReplacement("Go")
        until("typed") { composer.state.value.text == "Go" }
        until("Send enabled") { enabled(DraftComposerTags.Send) }
        tap(DraftComposerTags.Send)
        until("one create went out") { client.creates.size == 1 }
        val create = client.creates.single()
        assertEquals("work", create.profileId)
        assertEquals("claude", create.provider)
        assertEquals("m2", create.model)
    }

    @Test
    fun everyRetryTapSendsLikeTheWebAndASocketChangeDropsIt() {
        val failed = DraftFixtures.catalog.map {
            if (it.key == "codex") it.copy(status = "error", models = emptyList(), error = "codex app-server timed out", fetchedAt = 100) else it
        }
        client.push(failed)
        openBrowser()
        tap(NEW_SESSION_ROW_TAG + "codex")
        awaitTag(ModelBrowserTags.error("codex"))
        tap(ModelBrowserTags.retry("codex"))
        until("one refresh went out") { client.refreshes.size == 1 }
        // ta-coik.4: a double tap sends twice, as the web's Retry (no 2 s debounce, no in-flight hold).
        tap(ModelBrowserTags.retry("codex"))
        until("the second tap went out too") { client.refreshes.size == 2 }
        assertEquals(listOf("codex" to 1L, "codex" to 1L), client.refreshes.toList())
        // The socket is replaced: nothing is resent on the new one.
        rule.runOnUiThread { client.newSocket() }
        until("the old catalog is no longer shown") { !exists(ModelBrowserTags.error("codex")) }
        rule.waitForIdle()
        assertEquals(2, client.refreshes.size)
        // The new socket's own catalog: a tap there goes (drawn on socket 2).
        rule.runOnUiThread { client.push(failed) }
        awaitTag(ModelBrowserTags.retry("codex"))
        tap(ModelBrowserTags.retry("codex"))
        until("a refresh on the new socket") { client.refreshes.size == 3 }
        assertEquals("codex" to 2L, client.refreshes.last())
        // The settings panel's Refresh sends each tap as well.
        tap(ModelBrowserTags.cog("codex"))
        tap(ModelBrowserTags.refresh("codex"))
        until("the panel's Refresh went out") { client.refreshes.size == 4 }
    }

    @Test
    fun customIdsAreAddedRemovedAndFollowTheWebsRule() {
        openBrowser()
        tap(NEW_SESSION_ROW_TAG + "claude")
        tap(ModelBrowserTags.cog("claude"))
        awaitTag(ModelBrowserTags.settingsPanel("claude"))
        val add = ModelBrowserTags.addInput("claude")
        val plus = ModelBrowserTags.addSubmit("claude")
        fun refused(text: String, copy: String) {
            typeInto(add, text)
            until("why: $copy") { shown(ModelBrowserTags.AddProblem) == copy }
            rule.onNodeWithTag(plus, useUnmergedTree = true).assertIsNotEnabled()
            // Forced, it still adds nothing.
            rule.onNodeWithTag(plus, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
            assertTrue(composer.state.value.customModels.isEmpty())
        }
        refused("m1", "This provider already offers that model.")
        // ta-coik.4: the web's rule (model-browser.tsx:530-532): blank is refused, with no words.
        typeInto(add, "   ")
        rule.waitForIdle()
        rule.onNodeWithTag(plus, useUnmergedTree = true).assertIsNotEnabled()
        assertFalse(exists(ModelBrowserTags.AddProblem))
        // Taken, as on the web: two words, a bidi override, a zero-width character, past 200 bytes.
        for (ok in listOf("two words", "evil\u202Etxt.exe", "\u200Bhidden", "é".repeat(100) + "x")) {
            typeInto(add, ok)
            until("+ enabled for $ok") { enabled(plus) }
            assertFalse(exists(ModelBrowserTags.AddProblem))
            tap(plus)
            until("added: $ok") { composer.state.value.customModels["claude"].orEmpty().contains(ok) }
            rule.runOnUiThread { composer.removeCustomModel("claude", ok) }
            until("removed: $ok") { composer.state.value.customModels.isEmpty() }
        }
        typeInto(add, "  my-model[1m]  ")
        until("+ enabled") { enabled(plus) }
        tap(plus)
        until("added, trimmed") { composer.state.value.customModels == mapOf("claude" to listOf("my-model[1m]")) }
        until("the field cleared") { rule.onNodeWithTag(add, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text == "" }
        awaitTag(ModelBrowserTags.remove("claude", "my-model[1m]"))
        // Kept in this server's preferences, and listed as a model of the row.
        assertTrue((composer.preferences()["customModels"] as com.tether.app.protocol.tree.JsObj).containsKey("claude"))
        tap(ModelBrowserTags.cog("claude"))
        awaitTag(ModelBrowserTags.row("claude", "my-model[1m]"))
        tap(ModelBrowserTags.cog("claude"))
        tap(ModelBrowserTags.remove("claude", "my-model[1m]"))
        until("removed") { composer.state.value.customModels.isEmpty() }
        awaitGone(ModelBrowserTags.remove("claude", "my-model[1m]"))
        assertTrue("nothing went to the server", client.creates.isEmpty() && client.refreshes.isEmpty())
    }

    @Test
    fun hostileServerLabelsAreDrawnByTheTextRules() {
        val evilId = "x\u202Egpj.exe"
        val evilName = "\u202E" + "A".repeat(10_000)
        client.push(DraftFixtures.catalog.map { if (it.key == "claude") it.copy(label = "Claude\u2066\u202E (evil)", models = listOf(SessionModelOption(evilId, evilName))) else it })
        openBrowser()
        tap(NEW_SESSION_ROW_TAG + "claude")
        val row = ModelBrowserTags.row("claude", evilId)
        awaitTag(row)
        val said = spoken(row)
        assertFalse("no override reaches the screen", said.contains('\u202E') || said.contains('\u2066'))
        assertTrue("the label is cut: ${said.length}", said.length <= LABEL_BOUND)
        tap(row)
        awaitGone(ModelBrowserTags.Browser)
        until("picked") { formModel() == evilId }
        val chip = spoken(ModelBrowserTags.Chip)
        assertFalse(chip.contains('\u202E'))
        assertTrue(chip.length <= LABEL_BOUND + 40)
    }

    @Test
    fun aCatalogFromTheServerLeftBehindIsNeverShown() {
        openBrowser()
        tap(NEW_SESSION_ROW_TAG + "work")
        tap(ModelBrowserTags.row("work", "m1"))
        awaitGone(ModelBrowserTags.Browser)
        // Another server: its socket's catalog is not in; the old server's list is still in memory.
        rule.runOnUiThread {
            client.providerCatalogLive.value = false
            client.server.value = "https://other.test"
            client.consentOrigin.value = "https://other.test"
        }
        until("the draft is dropped") { formKey().isEmpty() }
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Sheet)
        tap(ModelBrowserTags.Chip)
        awaitTag(NEW_SESSION_PENDING_TAG)
        awaitTag(NEW_SESSION_ROW_TAG + "claude")
        assertFalse("no profile row of the old server", exists(NEW_SESSION_ROW_TAG + "work") || exists(NEW_SESSION_ROW_TAG + "personal"))
        typeInto(ModelBrowserTags.SearchAll, "Model 1")
        awaitTag(ModelBrowserTags.Empty)
        assertFalse("none of its models either", exists(ModelBrowserTags.row("work", "m1")) || exists(ModelBrowserTags.row("claude", "m1")))
    }

    @Test
    fun aPushUpdatesTheOpenBrowser() {
        openBrowser()
        tap(NEW_SESSION_ROW_TAG + "opencode")
        until("loading") { exists(ModelBrowserTags.Empty) }
        rule.runOnUiThread {
            client.push(DraftFixtures.catalog.map { if (it.key == "opencode") it.copy(status = "ready", models = listOf(SessionModelOption("oc-1", "OpenCode One"))) else it })
        }
        awaitTag(ModelBrowserTags.row("opencode", "oc-1"))
        assertTrue(exists(ModelBrowserTags.Browser))
        // A profile row the server drops disappears from the open list.
        tap(ModelBrowserTags.Back)
        awaitTag(NEW_SESSION_ROW_TAG + "work")
        rule.runOnUiThread { client.push(DraftFixtures.catalog.filter { it.key != "work" }) }
        awaitGone(NEW_SESSION_ROW_TAG + "work")
    }

    private companion object {
        /** LabelText.MAX_LABEL (80) plus the cleaned id as its description and the separator. */
        const val LABEL_BOUND = 100
    }
}
