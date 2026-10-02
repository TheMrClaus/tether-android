package com.tether.app.ui.draft

import androidx.compose.runtime.Composable
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
import com.tether.app.client.READINESS_NEED_BRANCH
import com.tether.app.client.READINESS_NEED_PR
import com.tether.app.client.READINESS_PR_TOO_LARGE
import com.tether.app.client.SETUP_BODY_MAY_PR
import com.tether.app.client.SETUP_CHANGED_COPY
import com.tether.app.client.SETUP_TITLE_WILL
import com.tether.app.client.WorktreeDeclaredScript
import com.tether.app.client.WorktreeField
import com.tether.app.client.WorktreeSourceInfo
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.settings.CONFIRM_ARM_MS
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

/** SafeText's inserted break opportunity (WORD JOINER + ZWSP): not content, so a comparison drops it. */
private const val BREAK = "\u2060\u200B"

/**
 * ta-23f (T8.1 slice 5): worktree isolation in the new-session sheet, over the real shell, view
 * model and draft engine, with a client that resolves the create as the real one does and records
 * every `worktree-inspect`. Every tap is a semantics action; every read after one waits on the model
 * or the drawn screen (v2 compose rule; never a single read).
 */
abstract class WorktreeHarness(private val width: Int, private val height: Int) {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val job = Job()
    private val prefs: UiPrefs by lazy {
        UiPrefs.on(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") })
    }
    protected val client = DraftTestClient()
    protected val vm by lazy { TetherViewModel(client) }
    protected val composer get() = vm.draftComposer

    private val window by lazy {
        object : WindowInfo {
            override val isWindowFocused: Boolean get() = true
            override val containerSize: IntSize get() = IntSize(width, height)
        }
    }

    @After fun closeStore() = runBlocking { job.cancel() }

    protected fun openSheet() {
        val content: @Composable () -> Unit = {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
        rule.setContent(content)
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Sheet)
        until("the root seeded the folder") { formStr("cwd") == DraftFixtures.ROOT }
    }

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

    protected fun formStr(key: String) = (composer.state.value.form[key] as? JsStr)?.value.orEmpty()

    protected fun isolated() = composer.state.value.form["useWorktree"] == JsBool.TRUE

    /** The text drawn under [tag] (merged, so a status line's glyph and words read as one), break marks removed. */
    protected fun shown(tag: String): String {
        val node = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
            ?: rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        return node?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty().replace(BREAK, "")
    }

    protected fun edited(tag: String): String = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** Pick the row and type a prompt through the engine (the model browser is ta-2uq's, tested there). */
    protected fun ready() {
        rule.runOnUiThread {
            composer.selectProviderAndModel("claude", "m1")
            composer.setText("Review this")
        }
        until("a row is picked") { formStr("key") == "claude" }
    }

    /** The worktree select, then its row for [mode]. */
    protected fun isolate(mode: String) {
        tap(WorktreeTags.Select)
        tap(DraftOptionsTags.option(mode))
        until("isolation is $mode") { if (mode == "local") !isolated() else isolated() && formStr("worktreeMode") == mode }
    }

    protected fun type(field: WorktreeField, text: String) {
        awaitTag(WorktreeTags.field(field))
        rule.onNodeWithTag(WorktreeTags.field(field), useUnmergedTree = true).performTextReplacement(text)
    }

    protected fun sendEnabled() = runCatching { rule.onNodeWithTag(DraftComposerTags.Send, useUnmergedTree = true).assertIsEnabled() }.isSuccess

    protected fun repo(hasSetup: Boolean, scripts: Int = 0, warnings: List<String> = emptyList(), branches: List<String> = listOf("origin/main", "origin/dev", "main", "feat/x")) =
        WorktreeSourceInfo(
            cwd = DraftFixtures.ROOT,
            isRepo = true,
            remote = "origin",
            defaultBaseRef = "origin/main",
            branches = branches,
            configPresent = hasSetup || scripts > 0 || warnings.isNotEmpty(),
            configWarnings = warnings,
            hasSetup = hasSetup,
            declaredScripts = List(scripts) { WorktreeDeclaredScript("s$it", "script", null) },
        )

    /** Answer the sheet's inspect (waited on), and wait for the engine to take it. */
    protected fun answer(info: WorktreeSourceInfo) {
        until("the sheet asked") { client.inspects.isNotEmpty() }
        client.answerInspect(info)
        until("the engine took the answer") { composer.state.value.worktreeSource == info }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class WorktreePhoneBehaviourTest : WorktreeHarness(412, 915) {

    @Test
    fun theSelectListsTheWebsModesAndLocalHidesTheDetails() {
        openSheet()
        assertFalse(exists(WorktreeTags.Details))
        tap(WorktreeTags.Select)
        for (value in listOf("local", "branch-off", "checkout-branch", "checkout-pr")) awaitTag(DraftOptionsTags.option(value))
        tap(DraftOptionsTags.option("checkout-pr"))
        until("isolated on a pull request") { isolated() && formStr("worktreeMode") == "checkout-pr" }
        awaitTag(WorktreeTags.Details)
        awaitTag(WorktreeTags.field(WorktreeField.Pr))
        awaitTag(WorktreeTags.field(WorktreeField.Slug))
        assertFalse("a pull request has no base", exists(WorktreeTags.field(WorktreeField.BaseRef)))
        isolate("branch-off")
        awaitTag(WorktreeTags.field(WorktreeField.BaseRef))
        awaitTag(WorktreeTags.field(WorktreeField.Branch))
        isolate("local")
        awaitGone(WorktreeTags.Details)
    }

    @Test
    fun isolationAsksAboutTheFolderOnceAndShowsTheWebsNotes() {
        openSheet()
        isolate("branch-off")
        until("one inspect for the folder on this socket") { client.inspects.size == 1 }
        assertEquals(DraftFixtures.ROOT to 1L, client.inspects.single().let { it.first to it.third })
        answer(repo(hasSetup = true, scripts = 2, warnings = listOf("scripts.web: port must be a number")))
        until("the setup note") { shown(WorktreeTags.SetupNote) == "Runs this project's setup before the first turn; 2 scripts you can run in the session." }
        assertEquals("scripts.web: port must be a number", shown(WorktreeTags.warning(0)))
        // A stale answer (an old request) changes nothing on screen.
        client.answerInspect(repo(hasSetup = false), requestId = "old")
        rule.waitForIdle()
        assertEquals(repo(hasSetup = true, scripts = 2, warnings = listOf("scripts.web: port must be a number")), composer.state.value.worktreeSource)
        // Another folder asks again; the first folder's answer is gone.
        rule.runOnUiThread { composer.setCwd("/srv/plain") }
        until("a second inspect for the new folder") { client.inspects.size == 2 && client.inspects.last().first == "/srv/plain" }
        until("the old answer is gone") { !exists(WorktreeTags.SetupNote) }
        client.answerInspect(WorktreeSourceInfo(cwd = "/srv/plain", isRepo = false))
        until("not a repository") { shown(WorktreeTags.NotRepo) == "/srv/plain is not a Git repository, so it cannot host an isolated session." }
        assertFalse("its fields give way to the note", exists(WorktreeTags.field(WorktreeField.Slug)))
        // A new socket asks again.
        rule.runOnUiThread { client.newSocket() }
        until("a third inspect, on socket 2") { client.inspects.size == 3 && client.inspects.last().third == 2L }
    }

    @Test
    fun anIncompleteWorktreeBlocksSendWithTheWebsWords() {
        openSheet()
        ready()
        isolate("checkout-branch")
        until("the branch is asked for") { shown(DraftComposerTags.Readiness) == READINESS_NEED_BRANCH }
        assertFalse(sendEnabled())
        isolate("checkout-pr")
        until("the number is asked for") { shown(DraftComposerTags.Readiness) == READINESS_NEED_PR }
        type(WorktreeField.Pr, "12a345678")
        until("digits only, as the web's input") { formStr("worktreePr") == "12345678" }
        until("past the server's limit") { shown(DraftComposerTags.Readiness) == READINESS_PR_TOO_LARGE }
        type(WorktreeField.Pr, "42")
        until("ready") { !exists(DraftComposerTags.Readiness) && sendEnabled() }
        assertTrue(client.creates.isEmpty())
    }

    @Test
    fun withoutSetupANewBranchSendsWithNoConfirmation() {
        openSheet()
        ready()
        isolate("branch-off")
        answer(repo(hasSetup = false, scripts = 1))
        until("the scripts note") { shown(WorktreeTags.SetupNote) == "This project declares 1 script you can run in the session." }
        until("Send enabled") { sendEnabled() }
        tap(DraftComposerTags.Send)
        until("the create went out") { client.creates.size == 1 }
        assertFalse(exists(WorktreeTags.Confirm))
        val frame = client.creates.single()
        assertEquals(true, frame.useWorktree)
        assertEquals("branch-off", frame.worktree?.mode)
    }

    @Test
    fun withSetupSendAsksFirstAndConfirmSendsOnce() {
        openSheet()
        ready()
        isolate("branch-off")
        answer(repo(hasSetup = true))
        until("Send enabled") { sendEnabled() }
        tap(DraftComposerTags.Send)
        awaitTag(WorktreeTags.Confirm)
        assertTrue("nothing sent before the confirmation", client.creates.isEmpty())
        assertTrue(exists(WorktreeTags.ConfirmKey))
        assertEquals(SETUP_TITLE_WILL, composer.state.value.setupConfirm?.title)
        assertEquals("origin/main", shown(WorktreeTags.confirmField("Base")))
        assertEquals(DraftFixtures.ROOT, shown(WorktreeTags.confirmField("Folder")))
        rule.mainClock.advanceTimeBy(CONFIRM_ARM_MS + 50)
        // A double tap on the armed key: one create.
        rule.onNodeWithTag(WorktreeTags.ConfirmKey, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        runCatching { rule.onNodeWithTag(WorktreeTags.ConfirmKey, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick) }
        until("the create went out") { client.creates.size == 1 }
        awaitGone(WorktreeTags.Confirm)
        rule.waitForIdle()
        assertEquals(1, client.creates.size)
        assertEquals("branch-off", client.creates.single().worktree?.mode)
    }

    @Test
    fun aTapBeforeTheConfirmationArmsSendsNothing() {
        openSheet()
        ready()
        isolate("checkout-pr")
        type(WorktreeField.Pr, "42")
        until("Send enabled") { sendEnabled() }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(DraftComposerTags.Send, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        awaitTag(WorktreeTags.ConfirmKey)
        rule.mainClock.advanceTimeBy(CONFIRM_ARM_MS - 150)
        rule.onNodeWithTag(WorktreeTags.ConfirmKey, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeByFrame()
        assertTrue("a tap inside the window sends nothing", client.creates.isEmpty())
        assertTrue(exists(WorktreeTags.Confirm))
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag(WorktreeTags.ConfirmKey, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.autoAdvance = true
        until("armed now: the same tap sends") { client.creates.size == 1 }
        assertEquals(42L, client.creates.single().worktree?.prNumber)
    }

    @Test
    fun cancelSendsNothingAndKeepsTheDraft() {
        openSheet()
        ready()
        isolate("checkout-pr")
        type(WorktreeField.Pr, "42")
        until("Send enabled") { sendEnabled() }
        tap(DraftComposerTags.Send)
        awaitTag(WorktreeTags.Confirm)
        assertEquals(SETUP_BODY_MAY_PR, composer.state.value.setupConfirm?.body)
        assertEquals("#42", shown(WorktreeTags.confirmField("Pull request")))
        tap(WorktreeTags.Cancel)
        awaitGone(WorktreeTags.Confirm)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue(client.creates.isEmpty())
        assertEquals("Review this", composer.state.value.text)
        assertTrue("the sheet stays", exists(DraftComposerTags.Sheet))
        assertNull(composer.state.value.setupConfirm)
    }

    @Test
    fun aNewAnswerWhileTheConfirmationIsOpenClosesItUnsent() {
        openSheet()
        ready()
        isolate("branch-off")
        answer(repo(hasSetup = true))
        until("Send enabled") { sendEnabled() }
        tap(DraftComposerTags.Send)
        awaitTag(WorktreeTags.Confirm)
        val id = composer.state.value.setupConfirmId
        // The server answers the same inspect again (its background refresh landed): what was shown may not hold.
        client.answerInspect(repo(hasSetup = false))
        awaitGone(WorktreeTags.Confirm)
        until("the sheet says why") { shown(DraftComposerTags.Error) == SETUP_CHANGED_COPY }
        rule.runOnUiThread { assertEquals(com.tether.app.client.DraftSubmitResult.Stale, composer.confirmSetup(id, DraftFixtures.ORIGIN)) }
        assertTrue(client.creates.isEmpty())
        // Sent again: now without setup on the default base, no confirmation at all.
        tap(DraftComposerTags.Send)
        until("the create went out") { client.creates.size == 1 }
    }

    @Test
    fun aSocketChangeWhileTheConfirmationIsOpenClosesItUnsent() {
        openSheet()
        ready()
        isolate("checkout-branch")
        type(WorktreeField.Branch, "feat/x")
        until("Send enabled") { sendEnabled() }
        tap(DraftComposerTags.Send)
        awaitTag(WorktreeTags.Confirm)
        rule.runOnUiThread { client.newSocket() }
        awaitGone(WorktreeTags.Confirm)
        rule.waitForIdle()
        assertTrue(client.creates.isEmpty())
    }

    @Test
    fun aHostileBranchNameIsShownAsTokens() {
        openSheet()
        ready()
        isolate("checkout-branch")
        val hostile = "feat/\u202Eevil\u200Bx"
        type(WorktreeField.Branch, hostile)
        until("the field holds it as typed") { formStr("worktreeBranch") == hostile }
        until("Send enabled") { sendEnabled() }
        tap(DraftComposerTags.Send)
        awaitTag(WorktreeTags.Confirm)
        val drawn = shown(WorktreeTags.confirmField("Branch"))
        assertTrue("RLO is a token: $drawn", drawn.contains("⟨U+202E⟩"))
        assertTrue("ZWSP is a token: $drawn", drawn.contains("⟨U+200B⟩"))
        assertFalse("no raw RLO is drawn", drawn.contains('\u202E'))
        assertEquals("the value confirmed is the value that would be sent", hostile, composer.state.value.setupConfirm?.ref)
    }

    @Test
    fun aSuggestionFillsTheFieldAndIsDrawnByTheExactRule() {
        openSheet()
        isolate("branch-off")
        answer(repo(hasSetup = false, branches = listOf("origin/main", "origin/\u202Edev", "main")))
        rule.onNodeWithTag(WorktreeTags.field(WorktreeField.BaseRef), useUnmergedTree = true).performSemanticsAction(SemanticsActions.RequestFocus)
        awaitTag(WorktreeTags.Suggestions)
        assertFalse("a local branch is no base suggestion", exists(WorktreeTags.suggestion("main")))
        val hostile = rule.onNodeWithTag(WorktreeTags.suggestion("origin/\u202Edev"), useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
        assertTrue("the suggestion shows its RLO as a token: $hostile", hostile.contains("⟨U+202E⟩"))
        tap(WorktreeTags.suggestion("origin/main"))
        until("the base is the pick") { formStr("worktreeBaseRef") == "origin/main" }
        assertTrue("a pick sends nothing", client.creates.isEmpty())
    }
}

/** ta-23f: the tablet's centred sheet carries the same controls and confirmation. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class WorktreeTabletBehaviourTest : WorktreeHarness(1280, 800) {

    @Test
    fun thePullRequestPathConfirmsAndSendsTheBlock() {
        openSheet()
        ready()
        isolate("checkout-pr")
        type(WorktreeField.Pr, " 0042 ")
        type(WorktreeField.Slug, "review")
        until("Send enabled") { sendEnabled() }
        tap(DraftComposerTags.Send)
        awaitTag(WorktreeTags.Confirm)
        rule.mainClock.advanceTimeBy(CONFIRM_ARM_MS + 50)
        tap(WorktreeTags.ConfirmKey)
        until("the create went out") { client.creates.size == 1 }
        val block = client.creates.single().worktree!!
        assertEquals("checkout-pr", block.mode)
        assertEquals(42L, block.prNumber)
        assertEquals("review", block.slug)
    }
}
