package com.tether.app.ui.draft

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.IntSize
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.MainShell
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-xki: the seeded catalog with reasoning-effort variants (Claude's m1, Codex's m1). */
internal object OptionsFixtures {
    val variants = listOf(ModelVariantOption("low", "Low"), ModelVariantOption("medium", "Medium"), ModelVariantOption("high", "High"))

    val catalog: List<ProviderCatalogEntry> = DraftFixtures.catalog.map { e ->
        when (e.key) {
            "work", "claude" -> e.copy(models = e.models.map { if (it.value == "m1") it.copy(variants = variants) else it })
            "codex" -> e.copy(models = e.models.map { if (it.value == "m1") it.copy(variants = listOf(ModelVariantOption("high", "High"))) else it })
            "opencode" -> e.copy(status = "ready", models = listOf(SessionModelOption("m1", "Big Pickle")))
            else -> e
        }
    }
}

/**
 * ta-xki (T8.1 slice 4): Effort and Mode over the real shell, view model and draft engine, with a
 * client that resolves the create as the real one does. Every tap is a semantics action; every read
 * after one waits on the model or the drawn screen (never a single read), under the v2 compose rule.
 */
abstract class DraftOptionsHarness(private val width: Int, private val height: Int) {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val job = Job()
    private val prefs: UiPrefs by lazy {
        UiPrefs.on(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") })
    }
    protected val client = DraftTestClient().apply { push(OptionsFixtures.catalog) }
    protected val vm by lazy { TetherViewModel(client) }
    protected val composer get() = vm.draftComposer

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(width, height)
    }

    @After fun closeStore() = runBlocking { job.cancel() }

    protected fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    protected fun until(what: String = "condition", condition: () -> Boolean) = try {
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            condition()
        }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("timed out waiting for: $what", e)
    }

    protected fun awaitTag(tag: String) = until("tag $tag") { exists(tag) }

    protected fun awaitGone(tag: String) = until("no tag $tag") { !exists(tag) }

    protected fun tap(tag: String) {
        awaitTag(tag)
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    protected fun spoken(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    protected fun state(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.StateDescription).orEmpty()

    protected fun selected(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Selected) == true

    protected fun form(key: String) = (composer.state.value.form[key] as? JsStr)?.value.orEmpty()

    protected fun open() {
        rule.setContent { TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } } }
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Sheet)
    }

    /** Pick [key]'s [model] through the Model chip's browser (on a phone, the settings sheet's). */
    protected fun pick(key: String, model: String = "m1") {
        tap(ModelBrowserTags.Chip)
        awaitTag(ModelBrowserTags.Browser)
        if (!exists(ModelBrowserTags.row(key, model))) {
            if (exists(ModelBrowserTags.Back)) tap(ModelBrowserTags.Back)
            tap(NEW_SESSION_ROW_TAG + key)
        }
        tap(ModelBrowserTags.row(key, model))
        awaitGone(ModelBrowserTags.Browser)
        until("picked $key / $model") { form("key") == key && form("model") == model }
    }

    protected fun typeAndSend(text: String) {
        awaitTag(DraftComposerTags.Input)
        rule.onNodeWithTag(DraftComposerTags.Input).performTextReplacement(text)
        until("typed") { composer.state.value.text == text }
        until("Send enabled") { runCatching { rule.onNodeWithTag(DraftComposerTags.Send, useUnmergedTree = true).assertIsEnabled() }.isSuccess }
        val before = client.creates.size
        tap(DraftComposerTags.Send)
        until("a create went out") { client.creates.size == before + 1 }
    }

    /** No confirmation of any kind stands between a pick and the draft (owner 2026-10-02). */
    protected fun assertNoConfirmation() {
        assertFalse(exists("escalation-confirm"))
    }
}

/** The phone (412dp): the settings sheet trigger row replaces the live row, as on the web below 64rem. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DraftOptionsPhoneBehaviourTest : DraftOptionsHarness(412, 915) {

    @Test
    fun thePhoneShowsTheSettingsTriggerInsteadOfTheLiveRow() {
        open()
        awaitTag(DraftOptionsTags.TriggerRow)
        assertFalse("no live row below 64rem", exists(DraftComposerTags.LiveRow))
        assertFalse("nothing to set before a row is picked", exists(DraftOptionsTags.SettingsChip))
        pick("claude")
        awaitTag(DraftOptionsTags.SettingsChip)
        // Claude starts on Auto (the web's default): the chip says so in words.
        until("the chip names Auto") { spoken(DraftOptionsTags.SettingsChip) == "Session settings, Auto is on" }
    }

    @Test
    fun modeAndEffortPickedInTheSheetRideTheCreate() {
        open()
        pick("work")
        tap(DraftOptionsTags.SettingsChip)
        awaitTag(DraftOptionsTags.Sheet)
        until("the hub lists Model, Effort and Mode") {
            exists(DraftOptionsTags.hubRow("Model")) && exists(DraftOptionsTags.hubRow("Effort")) && exists(DraftOptionsTags.hubRow("Mode"))
        }
        until("Effort reads Default untouched") { spoken(DraftOptionsTags.hubRow("Effort")) == "Effort: Default" }
        until("Mode reads Auto") { spoken(DraftOptionsTags.hubRow("Mode")) == "Mode: Auto" }
        tap(DraftOptionsTags.hubRow("Effort"))
        for (v in listOf("low", "medium", "high")) awaitTag(DraftOptionsTags.option(v))
        tap(DraftOptionsTags.option("high"))
        until("back on the hub with High") { spoken(DraftOptionsTags.hubRow("Effort")) == "Effort: High" }
        tap(DraftOptionsTags.hubRow("Mode"))
        for (v in listOf("default", "acceptEdits", "plan", "bypassPermissions")) awaitTag(DraftOptionsTags.option(v))
        until("Auto is the selected row") { selected(DraftOptionsTags.option("bypassPermissions")) }
        tap(DraftOptionsTags.option("acceptEdits"))
        until("back on the hub with Accept Edits") { spoken(DraftOptionsTags.hubRow("Mode")) == "Mode: Accept Edits" }
        assertNoConfirmation()
        tap(DraftOptionsTags.SheetClose)
        awaitGone(DraftOptionsTags.Sheet)
        until("an ordinary mode leaves the chip plain") { spoken(DraftOptionsTags.SettingsChip) == "Session settings: effort, mode" }
        typeAndSend("Refactor the sidebar")
        val create = client.creates.single()
        assertEquals("acceptEdits", create.permissionMode)
        assertEquals("high", create.reasoningEffort)
        assertNull("ta-93qs: Claude names no sandbox tier, as the web", create.sandboxPolicy)
        assertEquals("work", create.profileId)
    }

    @Test
    fun effortIsHiddenWhenTheModelHasNoVariant() {
        open()
        pick("claude", "m2")
        tap(DraftOptionsTags.SettingsChip)
        awaitTag(DraftOptionsTags.hubRow("Mode"))
        assertFalse(exists(DraftOptionsTags.hubRow("Effort")))
        // Back to a model with variants: Effort appears.
        tap(DraftOptionsTags.hubRow("Model"))
        awaitTag(ModelBrowserTags.row("claude", "m1"))
        tap(ModelBrowserTags.row("claude", "m1"))
        // Picked from the hub: the sheet returns to the hub (not closed).
        awaitTag(DraftOptionsTags.hubRow("Effort"))
        assertTrue(exists(DraftOptionsTags.Sheet))
    }

    @Test
    fun theOpencodeAutoRowIsBuildPlusApprovalNever() {
        open()
        pick("opencode")
        until("no elevated mode yet") { spoken(DraftOptionsTags.SettingsChip) == "Session settings: effort, mode" }
        tap(DraftOptionsTags.SettingsChip)
        tap(DraftOptionsTags.hubRow("Mode"))
        awaitTag(DraftOptionsTags.option("plan"))
        awaitTag(DraftOptionsTags.AutoRow)
        assertFalse("Auto is off", selected(DraftOptionsTags.AutoRow))
        until("the untouched mode is shown on Build") { selected(DraftOptionsTags.option("default")) }
        tap(DraftOptionsTags.AutoRow)
        until("the hub says Auto") { spoken(DraftOptionsTags.hubRow("Mode")) == "Mode: Auto" }
        assertEquals("bypassPermissions", form("mode"))
        tap(DraftOptionsTags.SheetClose)
        until("the chip says Auto") { spoken(DraftOptionsTags.SettingsChip) == "Session settings, Auto is on" }
        assertNoConfirmation()
        typeAndSend("Go")
        val create = client.creates.single()
        assertEquals("opencode", create.provider)
        assertEquals("bypassPermissions", create.permissionMode)
        assertEquals("never", create.approvalPolicy?.value)
        assertNull(create.sandboxPolicy)
    }

    @Test
    fun theHubSearchFindsEveryProvidersModelsAndBackStepsOut() {
        open()
        pick("claude")
        tap(DraftOptionsTags.SettingsChip)
        awaitTag(DraftOptionsTags.SheetSearch)
        rule.onNodeWithTag(DraftOptionsTags.SheetSearch, useUnmergedTree = true).performTextReplacement("Model 2")
        for (key in listOf("work", "personal", "claude", "codex")) awaitTag(ModelBrowserTags.row(key, "m2"))
        tap(ModelBrowserTags.row("codex", "m2"))
        until("the pick set the row and the model") { form("key") == "codex" && form("model") == "m2" }
        // From the hub: back to the hub, the search cleared.
        awaitTag(DraftOptionsTags.hubRow("Mode"))
        until("Codex's mode") { spoken(DraftOptionsTags.hubRow("Mode")) == "Mode: Default permissions" }
        tap(DraftOptionsTags.hubRow("Mode"))
        awaitTag(DraftOptionsTags.SheetBack)
        tap(DraftOptionsTags.SheetBack)
        awaitTag(DraftOptionsTags.hubRow("Model"))
        assertFalse(exists(DraftOptionsTags.SheetBack))
        tap(DraftOptionsTags.SheetClose)
        awaitGone(DraftOptionsTags.Sheet)
        assertTrue("nothing went to the server", client.creates.isEmpty())
    }

    @Test
    fun aServerRefusalOfAnElevatedModeKeepsTheTextAndNeverDowngrades() {
        open()
        pick("claude")
        until("Claude on Auto") { form("mode") == "bypassPermissions" }
        typeAndSend("Keep this prompt")
        assertEquals("bypassPermissions", client.creates.single().permissionMode)
        awaitTag(DraftComposerTags.Launching)
        // Before tether #236 deploys, a phone sign-in's elevated create is refused with an error.
        client.refuse("Skipping tool approvals needs a browser sign-in, not a paired device.")
        awaitTag(DraftComposerTags.Sheet)
        until("the server's words") { rule.onAllNodesWithTag(DraftComposerTags.Error).fetchSemanticsNodes().isNotEmpty() }
        until("the text is kept") { composer.state.value.text == "Keep this prompt" }
        assertEquals("the mode is never downgraded", "bypassPermissions", form("mode"))
        until("the chip still says Auto") { spoken(DraftOptionsTags.SettingsChip) == "Session settings, Auto is on" }
        assertEquals("never resent on its own", 1, client.creates.size)
        // Sent again by hand: the same posture, nothing quieter substituted.
        tap(DraftComposerTags.Send)
        until("a second create") { client.creates.size == 2 }
        assertEquals("bypassPermissions", client.creates.last().permissionMode)
        assertNull(client.creates.last().sandboxPolicy)
    }

    @Test
    fun choicesAreRememberedForTheRowInTheNextDraft() {
        open()
        pick("codex")
        tap(DraftOptionsTags.SettingsChip)
        tap(DraftOptionsTags.hubRow("Mode"))
        tap(DraftOptionsTags.option("full-access"))
        until("Full access") { form("mode") == "full-access" }
        tap(DraftOptionsTags.SheetClose)
        until("the chip names Full access") { spoken(DraftOptionsTags.SettingsChip) == "Session settings, Full access is on" }
        typeAndSend("one")
        client.answer("s1")
        until("the draft started over") { form("key").isEmpty() }
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Sheet)
        pick("codex")
        until("the row's remembered mode") { form("mode") == "full-access" }
        typeAndSend("two")
        assertEquals("off", client.creates.last().sandboxPolicy)
        assertEquals("never", client.creates.last().approvalPolicy?.value)
    }
}

/** The tablet (1280dp): the live row, as on the web from 64rem. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class DraftOptionsTabletBehaviourTest : DraftOptionsHarness(1280, 800) {

    @Test
    fun theLiveRowShowsEffortAndModeAndThePicksRideTheCreate() {
        open()
        pick("claude")
        awaitTag(DraftComposerTags.LiveRow)
        assertFalse(exists(DraftOptionsTags.TriggerRow))
        until("Mode is Claude's Auto") { spoken(DraftOptionsTags.Mode) == "Permission mode: Auto" }
        until("Effort reads Default") { spoken(DraftOptionsTags.Effort) == "Reasoning effort: Default" }
        assertFalse("Claude's Auto is a Mode row, not a chip", exists(DraftOptionsTags.Auto))
        tap(DraftOptionsTags.Mode)
        tap(DraftOptionsTags.option("plan"))
        until("Plan") { form("mode") == "plan" && spoken(DraftOptionsTags.Mode) == "Permission mode: Plan" }
        tap(DraftOptionsTags.Effort)
        tap(DraftOptionsTags.option("medium"))
        until("Medium") { form("reasoningEffort") == "medium" && spoken(DraftOptionsTags.Effort) == "Reasoning effort: Medium" }
        assertNoConfirmation()
        typeAndSend("Plan the sidebar work")
        val create = client.creates.single()
        assertEquals("plan", create.permissionMode)
        assertEquals("medium", create.reasoningEffort)
        assertNull("ta-93qs: Claude names no sandbox tier, as the web", create.sandboxPolicy)
    }

    @Test
    fun codexOffersItsThreePresetsAndFullAccessIsAnOrdinaryChoice() {
        open()
        pick("codex")
        until("Codex's default preset") { spoken(DraftOptionsTags.Mode) == "Codex mode: Default permissions" }
        tap(DraftOptionsTags.Mode)
        for (v in listOf("default", "auto-review", "full-access")) awaitTag(DraftOptionsTags.option(v))
        tap(DraftOptionsTags.option("full-access"))
        until("Full access") { form("mode") == "full-access" }
        assertNoConfirmation()
        typeAndSend("Go")
        val create = client.creates.single()
        assertEquals("off", create.sandboxPolicy)
        assertEquals("never", create.approvalPolicy?.value)
        assertNull(create.approvalsReviewer)
        assertEquals("bypassPermissions", create.permissionMode)
    }

    @Test
    fun theOpencodeAutoChipFlipsBuildAndAuto() {
        open()
        pick("opencode")
        awaitTag(DraftOptionsTags.Auto)
        until("off") { state(DraftOptionsTags.Auto).startsWith("Off") }
        until("the Mode select shows Build") { spoken(DraftOptionsTags.Mode) == "Permission mode: Build" }
        tap(DraftOptionsTags.Auto)
        until("on") { form("mode") == "bypassPermissions" && state(DraftOptionsTags.Auto).startsWith("On") }
        until("the Mode select still shows Build (modeDisplay)") { spoken(DraftOptionsTags.Mode) == "Permission mode: Build" }
        typeAndSend("Go")
        assertEquals("never", client.creates.single().approvalPolicy?.value)
        client.refuse("no")
        awaitTag(DraftComposerTags.Sheet)
        tap(DraftOptionsTags.Auto)
        until("off is Build") { form("mode") == "default" }
        tap(DraftComposerTags.Send)
        until("a second create") { client.creates.size == 2 }
        assertNull(client.creates.last().approvalPolicy)
        assertEquals("default", client.creates.last().permissionMode)
    }

    @Test
    fun effortIsHiddenWithoutVariantsAndOtherProvidersHaveNoMode() {
        open()
        pick("claude", "m3")
        awaitTag(DraftOptionsTags.Mode)
        assertFalse(exists(DraftOptionsTags.Effort))
        // Effort appears with a model that offers one variant (issue #44).
        pick("codex")
        awaitTag(DraftOptionsTags.Effort)
        tap(DraftOptionsTags.Effort)
        tap(DraftOptionsTags.option("high"))
        until("high") { form("reasoningEffort") == "high" }
    }
}
