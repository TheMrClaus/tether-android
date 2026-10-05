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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.IntSize
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tether.app.client.READINESS_NEED_BRANCH
import com.tether.app.client.READINESS_NEED_PR
import com.tether.app.client.WorktreeDeclaredScript
import com.tether.app.client.WorktreeField
import com.tether.app.client.WorktreeSourceInfo
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.MainShell
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
import org.junit.rules.RuleChain
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
 *
 * ta-m7ef (v143): an isolated Send first asks the server what the create would run (an intent
 * `worktree-inspect`) and creates with the consent the answer carries: at once with "none" when the ref
 * declares nothing, after "Run setup and start" when it declares hooks (draft-composer.tsx 1bf4a465).
 */
abstract class WorktreeHarness(private val width: Int, private val height: Int) {
    // ta-9dpl: the folder is the outer rule, deleted only once the composition is gone: a preference
    // write still on the disk at the end can no longer fail (and fail the test) under a live screen.
    val tmp = TemporaryFolder()
    val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(rule)

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

    // --- run on the phone and the tablet -------------------------------------------------------------

    /** A repository whose ref declares nothing: the answer that lets the create go at once with "none". */
    protected fun declaresNothing() = repo(hasSetup = false).copy(
        setupPreview = com.tether.app.client.WorktreeSetupPreview(
            "branch-off", "origin", "origin/main", "c".repeat(40), emptyList(), emptyList(), null, true, null, false, null, "none", "none", null,
        ),
    )

    protected val digest = "sha256:" + "ab".repeat(32)

    /** A repository whose ref declares setup, a teardown and a port script, and the consent that approves them. */
    protected fun declaresHooks(commands: List<String> = listOf("pnpm install", "pnpm run build"), hidden: Boolean = false) = repo(hasSetup = true).copy(
        setupPreview = com.tether.app.client.WorktreeSetupPreview(
            "branch-off", "origin", "origin/main", "c".repeat(40), commands, listOf("make clean"), "/opt/ports.sh", true, "f".repeat(64), hidden, digest, digest, null, null,
        ),
    )

    /** One tap on Send: the setup check goes out, nothing is created, and the check says so. */
    protected fun sendAndCheck() {
        until("Send enabled") { sendEnabled() }
        tap(DraftComposerTags.Send)
        until("the setup check went out") { client.setupChecks.size == 1 }
        awaitTag(DraftComposerTags.SetupChecking)
        assertTrue("nothing is created while it checks", client.creates.isEmpty())
        assertFalse("Send is off while it checks", sendEnabled())
    }

    /** One tap on Send, a "nothing declared" answer: one create, with consent none, no error. */
    protected fun sendOnce(): com.tether.app.protocol.ClientMessage.Create {
        sendAndCheck()
        client.answerSetup(declaresNothing())
        until("the create went out") { client.creates.size == 1 }
        rule.waitForIdle()
        assertEquals(1, client.creates.size)
        assertEquals("no error", "", composer.state.value.error)
        assertEquals("none", client.creates.single().setupConsent)
        assertFalse("no approval was asked for", exists(DraftComposerTags.SetupConfirm))
        return client.creates.single()
    }

    @Test
    fun withSetupTheNoteSaysSoAndSendChecksFirst() {
        openSheet()
        ready()
        isolate("branch-off")
        answer(repo(hasSetup = true))
        until("the web's note") { shown(WorktreeTags.SetupNote) == "Runs this project's setup before the first turn." }
        val frame = sendOnce()
        assertEquals(true, frame.useWorktree)
        assertEquals("branch-off", frame.worktree?.mode)
        assertNull("the frame is the web's", frame.worktree?.baseRef)
    }

    /** The case ta-23f r2 confirmed (only an upstream remote): the same check, frame unchanged. */
    @Test
    fun anUpstreamOnlyRepoSendsAfterItsCheck() {
        openSheet()
        ready()
        isolate("branch-off")
        answer(repo(hasSetup = false).copy(remote = "upstream", remotes = listOf("upstream"), defaultBaseRef = "upstream/main"))
        val block = sendOnce().worktree!!
        assertEquals("branch-off", block.mode)
        assertNull("the frame stays the web's", block.baseRef)
    }

    /** No folder answer yet: the create's own check is what decides. */
    @Test
    fun aPullRequestWithNoFolderAnswerSendsAfterItsCheck() {
        openSheet()
        ready()
        isolate("checkout-pr")
        type(WorktreeField.Pr, "42")
        assertNull(composer.state.value.worktreeSource)
        assertEquals(42L, sendOnce().worktree?.prNumber)
    }

    /**
     * The note is drawn exactly when the web draws it (draft-composer.tsx 90fbb9f:789-795): inside
     * WorktreeDetails, only for a repository, when the committed config is present and declares setup
     * or scripts; after the Name field, before the config warnings.
     */
    @Test
    fun theSetupNoteShowsExactlyWhenTheWebShowsIt() {
        openSheet()
        isolate("branch-off")
        until("the sheet asked") { client.inspects.isNotEmpty() }
        val rows = listOf(
            repo(hasSetup = true) to "Runs this project's setup before the first turn.",
            repo(hasSetup = true, scripts = 1) to "Runs this project's setup before the first turn; 1 script you can run in the session.",
            repo(hasSetup = false, scripts = 2) to "This project declares 2 scripts you can run in the session.",
            repo(hasSetup = false) to null,
            repo(hasSetup = false, warnings = listOf("bad config")) to null,
            repo(hasSetup = true).copy(configPresent = false) to null,
            repo(hasSetup = true, scripts = 2).copy(isRepo = false) to null,
        )
        for ((info, note) in rows) {
            client.answerInspect(info)
            until("the engine took $info") { composer.state.value.worktreeSource == info }
            rule.waitForIdle()
            if (note == null) {
                until("no note for $info") { !exists(WorktreeTags.SetupNote) }
            } else {
                until("the note for $info") { shown(WorktreeTags.SetupNote) == note }
                // Its place: after the Name field (the web's order), in the details row.
                val name = rule.onNodeWithTag(WorktreeTags.field(WorktreeField.Slug), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val drawn = rule.onNodeWithTag(WorktreeTags.SetupNote, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("the note follows the Name field: $name / $drawn", drawn.top >= name.bottom || (drawn.top >= name.top && drawn.left >= name.right))
            }
        }
        // Local: no details, no note.
        isolate("local")
        awaitGone(WorktreeTags.Details)
        assertFalse(exists(WorktreeTags.SetupNote))
    }

    /** Verifier F3: the New branch placeholder is the web's `tether/<name>` (U+003C / U+003E), drawn as is. */
    @Test
    fun theNewBranchPlaceholderIsTheWebsText() {
        openSheet()
        ready()
        isolate("branch-off")
        until("the placeholder") { rule.onAllNodesWithText("tether/<name>", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText("tether/\u2039name\u203A", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
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
        // ta-coik.4: the web's Number.parseInt rule, no app limit (the server validates).
        until("ready past the retired 9,999,999 limit") { !exists(DraftComposerTags.Readiness) && sendEnabled() }
        type(WorktreeField.Pr, "0")
        until("0 asks for the number") { shown(DraftComposerTags.Readiness) == READINESS_NEED_PR }
        type(WorktreeField.Pr, "42")
        until("ready") { !exists(DraftComposerTags.Readiness) && sendEnabled() }
        assertTrue(client.creates.isEmpty())
    }

    @Test
    fun withoutSetupANewBranchSendsAfterItsCheckWithNoApproval() {
        openSheet()
        ready()
        isolate("branch-off")
        answer(repo(hasSetup = false, scripts = 1))
        until("the scripts note") { shown(WorktreeTags.SetupNote) == "This project declares 1 script you can run in the session." }
        val frame = sendOnce()
        assertEquals(true, frame.useWorktree)
        assertEquals("branch-off", frame.worktree?.mode)
    }

    // --- ta-m7ef: the approval ---------------------------------------------------------------------

    @Test
    fun declaredHooksAreShownForApprovalAndTheCreateCarriesExactlyTheirConsent() {
        openSheet()
        ready()
        isolate("checkout-pr")
        type(WorktreeField.Pr, "42")
        sendAndCheck()
        // The check asked for THIS create's block.
        val asked = client.setupChecks.single()
        assertEquals(DraftFixtures.ROOT, asked[0])
        assertEquals(com.tether.app.protocol.WorktreeCreateRequest("checkout-pr", prNumber = 42), asked[1])
        client.answerSetup(declaresHooks().copy(setupPreview = declaresHooks().setupPreview!!.copy(mode = "checkout-pr")))
        awaitTag(DraftComposerTags.SetupConfirm)
        awaitGone(DraftComposerTags.SetupChecking)
        // The ref, the commit and every command, one numbered block each; teardown is shown for information.
        until("the title") { shown(com.tether.app.ui.components.ConsentTags.Title) == SetupConfirmationCopy.TITLE }
        until("the ref and commit") {
            shown(com.tether.app.ui.components.ConsentTags.Body).let {
                it.startsWith("Pull request #42 from origin/main at commit ${"c".repeat(40)} declares commands in its committed tether.json.")
            }
        }
        assertEquals("pnpm install", shownCommand("draft-setup-command", 0))
        assertEquals("pnpm run build", shownCommand("draft-setup-command", 1))
        assertEquals("make clean", shownCommand("draft-teardown-command", 0))
        assertEquals("/opt/ports.sh", shownCommand("draft-port-script", 0))
        assertTrue("no hidden characters, no warning", !exists(com.tether.app.ui.components.ConsentTags.Hidden))
        assertTrue("nothing is created before the approval", client.creates.isEmpty())
        assertFalse("Send stays off while it waits", sendEnabled())
        tap(DraftComposerTags.SetupRun)
        until("the create went out") { client.creates.size == 1 }
        assertEquals(digest, client.creates.single().setupConsent)
        assertEquals(42L, client.creates.single().worktree?.prNumber)
        awaitGone(DraftComposerTags.SetupConfirm)
    }

    @Test
    fun cancelOnTheApprovalCreatesNothingAndKeepsTheDraft() {
        openSheet()
        ready()
        isolate("branch-off")
        sendAndCheck()
        client.answerSetup(declaresHooks())
        awaitTag(DraftComposerTags.SetupConfirm)
        tap(DraftComposerTags.SetupCancel)
        awaitGone(DraftComposerTags.SetupConfirm)
        assertTrue(client.creates.isEmpty())
        assertEquals("Review this", composer.state.value.text)
        until("Send is back") { sendEnabled() }
    }

    @Test
    fun hiddenCharactersAreWarnedAboutAndDrawnAsTokens() {
        openSheet()
        ready()
        isolate("branch-off")
        sendAndCheck()
        client.answerSetup(declaresHooks(commands = listOf("echo \u0430pi \u202Eok"), hidden = true))
        awaitTag(DraftComposerTags.SetupConfirm)
        awaitTag(com.tether.app.ui.components.ConsentTags.Hidden)
        assertEquals(com.tether.app.ui.components.HIDDEN_CHARACTERS_WARNING, shown(com.tether.app.ui.components.ConsentTags.Hidden))
        assertEquals("echo U+0430pi U+202Eok", shownCommand("draft-setup-command", 0))
    }

    @Test
    fun anIntentThatDidNotResolveShowsTheServersWordsAndCreatesNothing() {
        openSheet()
        ready()
        isolate("checkout-branch")
        type(WorktreeField.Branch, "feat/x")
        sendAndCheck()
        client.answerSetup(repo(hasSetup = false).copy(setupPreview = declaresNothing().setupPreview!!.copy(consent = null, error = "No such branch: feat/x")))
        until("the words") { shown(DraftComposerTags.Error).contains("No such branch: feat/x") }
        awaitGone(DraftComposerTags.SetupChecking)
        assertTrue(client.creates.isEmpty())
        until("Send is back") { sendEnabled() }
    }

    @Test
    fun anEditWhileCheckingCancelsItWithTheWebsWords() {
        openSheet()
        ready()
        isolate("branch-off")
        sendAndCheck()
        rule.runOnUiThread { composer.setText("Review that") }
        awaitGone(DraftComposerTags.SetupChecking)
        until("the words") { shown(DraftComposerTags.Error).contains("The setup check was cancelled because the draft changed. Press Send again.") }
        // The late answer to the cancelled check starts nothing.
        client.answerSetup(declaresNothing())
        rule.waitForIdle()
        assertTrue(client.creates.isEmpty())
    }

    private fun shownCommand(prefix: String, index: Int): String = shown("$prefix:$index")

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

/** ta-23f: the tablet's centred sheet carries the same controls; ta-coik.11: Send sends at once there too. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class WorktreeTabletBehaviourTest : WorktreeHarness(1280, 800) {

    @Test
    fun thePullRequestPathSendsTheBlockAtOnce() {
        openSheet()
        ready()
        isolate("checkout-pr")
        answer(repo(hasSetup = true))
        type(WorktreeField.Pr, " 0042 ")
        type(WorktreeField.Slug, "review")
        val block = sendOnce().worktree!!
        assertEquals("checkout-pr", block.mode)
        assertEquals(42L, block.prNumber)
        assertEquals("review", block.slug)
    }
}
