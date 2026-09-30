package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.RunCommandResult
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.protocol.reduce.ev
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7.3 behaviour of the composer's commands, against tether components/chat-view.tsx (v128): the
 * `!` command mode only where the server offers it, Enter runs in the foreground and the Background
 * key detached, both keys armed and inert on a copy that is not live; while a FOREGROUND command
 * runs, Interrupt reads "Stop" (the turn-bound interrupt) and Background replaces Queue (also
 * Ctrl+B); the slash palette on every engine, fed by the CLI inventory, with the web's passthrough,
 * blocked and desync rules; the `@` Agents picker and its delegate chip.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class CommandComposerBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val sends = mutableListOf<String>()
    private val interrupts = mutableListOf<String>()
    private val rec = CommandFixtures.Recorder()
    private val controlsRec = SessionControlFixtures.Recorder()
    private var fixture by mutableStateOf(ComposerFixtures.idle)
    private var liveness by mutableStateOf(ComposerLiveness.Live)
    private var agents by mutableStateOf(emptyList<ProviderCatalogEntry>())
    private var warmReads = 0

    private fun show(
        start: ChatFixtures.Folded = ComposerFixtures.idle,
        commandMode: Boolean = true,
        session: AgentSession = ComposerFixtures.session,
        controls: ServerMessage.SessionControls? = null,
        codex: com.tether.app.client.ProviderControlsState<com.tether.app.client.CodexSnapshot>? = null,
    ) {
        fixture = start
        rule.setContent {
            ComposerHost(TetherSkin.Machine) {
                Composer(
                    session = session,
                    projection = fixture.projection,
                    controls = controls,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { text, _ -> sends += text; true },
                    onInterrupt = { turnId -> interrupts += turnId; com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = liveness,
                    tree = fixture.tree,
                    controlActions = controlsRec.actions(codex = codex),
                    runActions = rec.actions(commandMode = commandMode, agents = agents),
                    onWarmControls = { warmReads++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun input() = rule.onNodeWithContentDescription("Message the agent")
    private fun inputText(): String = input().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    private fun arm() {
        rule.mainClock.advanceTimeBy(CONSENT_ARM_DELAY_MS + 100)
        rule.waitForIdle()
    }

    // --- `!` command mode ---------------------------------------------------------------------

    @Test
    fun aBangDraftBecomesACommandAndEnterRunsItInTheForeground() {
        show()
        rule.onAllNodesWithTag(COMMAND_FLAG_TAG).assertCountEquals(0)
        input().performTextInput("!  npm test  ")
        rule.onNodeWithTag(COMMAND_FLAG_TAG).assertExists()
        rule.onNodeWithText(COMMAND_MODE_FLAG).assertExists()
        // The idle keys: Send to agent + Background (icon-only on a phone, named for TalkBack).
        rule.onNodeWithContentDescription("Run command and send output to the agent").assertExists()
        rule.onNodeWithContentDescription("Run command in the background").assertExists()
        rule.onAllNodesWithContentDescription("Send message").assertCountEquals(0)
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("npm test" to false), rec.runs)
        assertEquals(emptyList<String>(), sends)
        assertEquals("", inputText())
    }

    @Test
    fun theBackgroundKeyRunsDetachedOnceArmed() {
        show()
        input().performTextInput("!sleep 30")
        // Not armed yet: a tap in the first 500 ms does nothing.
        rule.onNodeWithTag(RUN_BACKGROUND_KEY_TAG).performClick()
        rule.waitForIdle()
        assertEquals(emptyList<Pair<String, Boolean>>(), rec.runs)
        arm()
        rule.onNodeWithTag(RUN_BACKGROUND_KEY_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf("sleep 30" to true), rec.runs)
    }

    @Test
    fun theSoftKeyboardSendRunsTheCommandToo() {
        show()
        input().performTextInput("!ls -la")
        input().performImeAction()
        rule.waitForIdle()
        assertEquals(listOf("ls -la" to false), rec.runs)
    }

    @Test
    fun withoutTheServersCommandRunnerABangIsAnOrdinaryMessage() {
        show(commandMode = false)
        input().performTextInput("!ls")
        rule.onAllNodesWithTag(COMMAND_FLAG_TAG).assertCountEquals(0)
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("!ls"), sends)
        assertEquals(emptyList<Pair<String, Boolean>>(), rec.runs)
    }

    @Test
    fun aBareBangRunsNothingAndSaysWhy() {
        show()
        input().performTextInput("!  ")
        rule.onNodeWithTag(RUN_KEY_TAG).assertIsNotEnabled()
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue(rec.runs.isEmpty())
        rule.onNodeWithText("Type a command after “!” to run it.").assertExists()
    }

    @Test
    fun anOversizedCommandSaysTheLimit() {
        show()
        input().performTextInput("!echo " + "x".repeat(com.tether.app.client.CommandGuard.COMMAND_MAX_BYTES))
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue(rec.runs.isEmpty())
        rule.onNodeWithText(COMMAND_TOO_LONG_COPY).assertExists()
    }

    @Test
    fun aCopyThatIsNotLiveRunsNothing() {
        liveness = ComposerLiveness(interruptLock = "Connect to run it. This is a saved copy.", stale = null)
        show()
        input().performTextInput("!npm test")
        arm()
        rule.onNodeWithContentDescription("Run command and send output to the agent, unavailable: Connect to run it. This is a saved copy.").assertExists()
        rule.onNodeWithTag(RUN_KEY_TAG).performClick()
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue(rec.runs.isEmpty())
        rule.onNodeWithText("Connect to run it. This is a saved copy.").assertExists()
        assertEquals("the draft is kept", "!npm test", inputText())
    }

    @Test
    fun aForegroundRunWaitsForTheTurn() {
        show(ComposerFixtures.busy)
        input().performTextInput("!npm test")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue(rec.runs.isEmpty())
        rule.onNodeWithText("Wait for the current turn to finish, or use “Send to background”.").assertExists()
    }

    @Test
    fun aRefusedRunKeepsTheDraftAndSaysWhy() {
        rec.run = RunCommandResult.NotOffered
        show()
        input().performTextInput("!npm test")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(1, rec.runs.size)
        assertEquals("!npm test", inputText())
        rule.onNodeWithText("Command mode isn’t offered for this session — the command was not run.").assertExists()
    }

    // --- the foreground command's keys ----------------------------------------------------------

    @Test
    fun whileAForegroundCommandRunsInterruptReadsStopAndBackgroundReplacesQueue() {
        show(CommandFixtures.running)
        arm()
        rule.onAllNodesWithContentDescription("Interrupt the current turn").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Queue message").assertCountEquals(0)
        rule.onNodeWithContentDescription("Stop the command").performClick()
        assertEquals("Stop is the turn-bound interrupt", listOf("t1"), interrupts)
        rule.onNodeWithContentDescription("Send this command to the background").performClick()
        rule.waitForIdle()
        assertEquals(listOf("t1"), rec.backgrounds)
    }

    @Test
    fun anOrdinaryTurnKeepsQueueAndInterrupt() {
        show(ComposerFixtures.busy)
        arm()
        rule.onAllNodesWithTag(BACKGROUND_KEY_TAG).assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Stop the command").assertCountEquals(0)
        rule.onNodeWithContentDescription("Interrupt the current turn").assertExists()
    }

    @Test
    fun theForegroundKeysAreArmedAndFollowTheLock() {
        show(CommandFixtures.running)
        rule.onNodeWithTag(BACKGROUND_KEY_TAG).performClick()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        rule.waitForIdle()
        assertTrue("nothing in the first 500 ms", rec.backgrounds.isEmpty() && interrupts.isEmpty())
        liveness = ComposerLiveness(interruptLock = "Catching up…", stale = null)
        arm()
        rule.onNodeWithTag(BACKGROUND_KEY_TAG).performClick()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).performClick()
        rule.waitForIdle()
        assertTrue("a copy that is not live stops and moves nothing", rec.backgrounds.isEmpty() && interrupts.isEmpty())
    }

    @Test
    fun ctrlBBackgroundsOnlyARunningForegroundCommand() {
        show(ComposerFixtures.busy)
        input().performTextInput("x")
        input().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        rule.waitForIdle()
        assertTrue(rec.backgrounds.isEmpty())
        fixture = CommandFixtures.running
        rule.waitForIdle()
        input().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        rule.waitForIdle()
        assertEquals(listOf("t1"), rec.backgrounds)
    }

    // --- the slash palette ----------------------------------------------------------------------

    @Test
    fun theInventoryFeedsThePaletteAndItsChangesAndResetFollow() {
        show(CommandFixtures.inventory)
        input().performTextInput("/")
        rule.waitForIdle()
        assertEquals("the palette asks for the warm list once", 1, warmReads)
        rule.onNodeWithTag("composer-slash-menu").assertExists()
        // Supported first (by name), then the blocked /exit with its "terminal only" tag.
        rule.onNodeWithContentDescription("/compact <instructions>, Clear history but keep a summary, Tether").assertExists()
        rule.onNodeWithContentDescription("/context, Show context usage, Tether").assertExists()
        rule.onNodeWithContentDescription("/exit, Exit the REPL, terminal only").assertExists()
        fixture = CommandFixtures.inventoryChanged
        rule.waitForIdle()
        rule.onNodeWithContentDescription("/review, Review a pull request, Tether").assertExists()
        rule.onAllNodesWithContentDescription("/compact, Clear history but keep a summary, Tether").assertCountEquals(0)
        fixture = CommandFixtures.inventoryReset
        rule.waitForIdle()
        // Reset: back to the controls reply (none here) plus the guaranteed /model.
        rule.onNodeWithContentDescription("/model [model], Switch the model for this session, Tether").assertExists()
        rule.onAllNodesWithContentDescription("/review, Review a pull request, Tether").assertCountEquals(0)
    }

    @Test
    fun serverCommandTextIsDrawnCleanAndAnUncleanNameIsNeverOffered() {
        // r2: a name that is not already clean is not offered (completing it would put raw server
        // text in the draft); a clean name's hint and description are drawn clean.
        val hostile = ServerMessage.SessionControls(
            "sess-0001", emptyList(),
            listOf(
                SessionCommandOption("rev\u202Eiew", "spoofed", null, null, true),
                SessionCommandOption("re\u200Bset", "zero width", null, null, true),
                SessionCommandOption("revert", "line1\nline2", "<\u202Esha>", null, true),
            ),
            model = null,
        )
        show(ComposerFixtures.idle, controls = hostile)
        input().performTextInput("/re")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("/revert <sha>, line1 line2, Tether").assertExists()
        rule.onAllNodesWithContentDescription("/review, spoofed, Tether").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("/reset, zero width, Tether").assertCountEquals(0)
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("/revert ", inputText())
    }

    @Test
    fun theOfferableRuleTakesOnlyCleanNames() {
        assertTrue(offerableCommand(SessionCommandOption("security-review")))
        assertTrue(!offerableCommand(SessionCommandOption("a b")))
        assertTrue(!offerableCommand(SessionCommandOption("")))
        assertTrue(!offerableCommand(SessionCommandOption("x\u0007")))
        assertTrue(!offerableCommand(SessionCommandOption("rev\u2066iew")))
    }

    @Test
    fun anAdvertisedCommandIsForwardedAsPromptTextAndAcceptingOneCompletesIt() {
        show(CommandFixtures.inventory)
        input().performTextInput("/con")
        rule.waitForIdle()
        input().performKeyInput { pressKey(Key.Enter) } // takes the first match
        rule.waitForIdle()
        assertEquals("/context ", inputText())
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("/context"), sends)
    }

    @Test
    fun aBlockedCommandIsRefusedAndADesyncOneWarnsThenForwards() {
        show(CommandFixtures.inventory)
        input().performTextInput("/exit now")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue(sends.isEmpty())
        rule.onNodeWithText("/exit would end this session — run it from a terminal instead.").assertExists()
        input().performTextInput("/compact keep the plan")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("/compact keep the plan"), sends)
        rule.onNodeWithText("/compact changes what the model has in context — the transcript above is kept, but no longer matches it.").assertExists()
    }

    @Test
    fun anUnlistedSlashIsOrdinaryPromptTextAsOnTheWeb() {
        show(CommandFixtures.inventory)
        input().performTextInput("/does-not-exist please")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("/does-not-exist please"), sends)
    }

    @Test
    fun codexOffersOnlyCompactionAndRunsItAsTheGuardedControl() {
        val codex = SessionControlFixtures.codex
        val ready = SessionControlFixtures.codexState
        show(ComposerFixtures.idle, session = codex, codex = ready)
        input().performTextInput("/")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("/compact, Summarize conversation to prevent hitting the context limit, Tether").assertExists()
        input().performTextInput("compact")
        input().performKeyInput { pressKey(Key.Enter) } // the open palette takes it: "/compact "
        rule.waitForIdle()
        assertEquals("/compact ", inputText())
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf(com.tether.app.client.SessionControl.CodexCompaction(ready.snapshot!!.revision)), controlsRec.sent)
        assertTrue("never sent as prompt text", sends.isEmpty())
    }

    // --- the @ Agents picker --------------------------------------------------------------------

    @Test
    fun anAtTokenOpensTheAgentsAndAPickBecomesTheDelegateChip() {
        agents = CommandFixtures.catalog
        show()
        input().performTextInput("Please check this @cla")
        rule.waitForIdle()
        rule.onNodeWithTag(MENTION_MENU_TAG).assertExists()
        rule.onNodeWithContentDescription("Claude, Opus 5, delegate").assertExists()
        rule.onAllNodesWithContentDescription("Codex, loading models…, delegate").assertCountEquals(0) // filtered by the query
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("the token is stripped", "Please check this", inputText())
        rule.onNodeWithTag(DELEGATE_BAR_TAG).assertExists()
        rule.onNodeWithContentDescription("Delegate to Claude, Opus 5, review").assertExists()
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("Please check this" to com.tether.app.protocol.DelegateMention("claude", "review", "claude-opus-5")), rec.delegations)
        assertTrue("never the ordinary send", sends.isEmpty())
        rule.onAllNodesWithTag(DELEGATE_BAR_TAG).assertCountEquals(0)
    }

    @Test
    fun theAgentsAreAskedForWhenThePickerOpensWithoutACatalog() {
        show()
        input().performTextInput("@")
        rule.waitForIdle()
        assertEquals(1, rec.agentReads)
        rule.onAllNodesWithTag(MENTION_MENU_TAG).assertCountEquals(0)
    }

    @Test
    fun aDelegationWaitsForTheTurnAndARefusalKeepsEverything() {
        agents = CommandFixtures.catalog
        show(ComposerFixtures.busy)
        input().performTextInput("@open")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("OpenCode, 2 models, delegate").performClick()
        input().performTextInput("audit the tests")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue(rec.delegations.isEmpty())
        rule.onNodeWithText("Wait for the current turn to finish before delegating.").assertExists()
        fixture = ComposerFixtures.idle
        rec.delegated = false
        rule.waitForIdle()
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(1, rec.delegations.size)
        assertEquals("audit the tests", inputText())
        rule.onNodeWithTag(DELEGATE_BAR_TAG).assertExists()
        rule.onNodeWithText("That agent isn’t offered for this session any more — nothing was sent.").assertExists()
    }

    @Test
    fun noPickerInCommandModeOrOnAReadOnlySession() {
        agents = CommandFixtures.catalog
        show()
        input().performTextInput("!echo @cl")
        rule.waitForIdle()
        rule.onAllNodesWithTag(MENTION_MENU_TAG).assertCountEquals(0)
    }

    @Test
    fun theChipsXDropsTheDelegation() {
        agents = CommandFixtures.catalog
        show()
        input().performTextInput("@cl")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Claude, Opus 5, delegate").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DELEGATE_BAR_TAG).assertExists()
        assertEquals("", inputText())
        rule.onNodeWithContentDescription("Remove the delegate mention").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(DELEGATE_BAR_TAG).assertCountEquals(0)
        input().performTextInput("hello")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("hello"), sends)
        assertTrue(rec.delegations.isEmpty())
    }

    // --- the transcript's command panel --------------------------------------------------------

    @Test
    fun theTranscriptCarriesTheCommandOutputBlock() {
        val items = buildChatItems(CommandFixtures.running.projection, CommandFixtures.running.tree, showThinking = false)
        val block = items.filterIsInstance<ChatItem.Block>().single { it.block.kind == COMMAND_OUTPUT_BLOCK }
        val view = commandOutputView(block.raw as JsObj)!!
        assertEquals(CommandFixtures.COMMAND, view.command)
        assertTrue(view.running)
        assertEquals("running…", view.status)
        val failed = commandOutputView(buildChatItems(CommandFixtures.failed.projection, CommandFixtures.failed.tree, showThinking = false)
            .filterIsInstance<ChatItem.Block>().single { it.block.kind == COMMAND_OUTPUT_BLOCK }.raw)!!
        assertEquals("exit 1", failed.status)
        assertTrue(failed.failed && failed.outputTruncated)
    }

    @Test
    fun thePanelDrawsTheOutputCleanAndSaysTheStatusInWords() {
        val view = commandOutputView(rawBlock(CommandFixtures.hostile))!!
        rule.setContent { ComposerHost(TetherSkin.Machine) { CommandOutputPanel(view) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Command ${commandLabel(CommandFixtures.COMMAND)}, running…").assertExists()
        val shown = rule.onNodeWithTag("command-panel-body").fetchSemanticsNode().let { node ->
            node.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("") { it.text }
        }
        assertEquals("ok\nApprove gnp.exe done", shown)
    }

    private fun rawBlock(f: ChatFixtures.Folded): JsObj =
        buildChatItems(f.projection, f.tree, showThinking = false).filterIsInstance<ChatItem.Block>().single { it.block.kind == COMMAND_OUTPUT_BLOCK }.raw!!

    @Test
    fun thePanelCleansOnlyABoundedRawTail() {
        val huge = com.tether.app.protocol.tree.JsArr.of(
            (0 until 40).map { i ->
                com.tether.app.protocol.tree.JsObj.of(
                    "stream" to com.tether.app.protocol.tree.JsStr(if (i % 2 == 0) "stdout" else "stderr"),
                    "text" to com.tether.app.protocol.tree.JsStr("$i:" + "y".repeat(50_000)),
                )
            },
        )
        val raw = commandPanelRawTail(huge)
        assertTrue(raw.segments.sumOf { it.text.length } <= 2 * COMMAND_PANEL_MAX_CHARS)
        val before = panelCharsCleaned.get()
        val shown = commandPanelText(huge)
        assertTrue("cleaned ${panelCharsCleaned.get() - before}", panelCharsCleaned.get() - before <= 2L * COMMAND_PANEL_MAX_CHARS)
        assertTrue(shown.segments.sumOf { it.text.length } <= COMMAND_PANEL_MAX_CHARS)
        assertTrue(shown.dropped > 0)
    }

    @Test
    fun anUnterminatedEscapeInTheStreamNeverHidesWhatFollows() {
        val f = ChatFixtures.fold(
            ev("turn_started", "t1", ts = CommandFixtures.T) {
                put("commandRun", buildJsonObject { put("command", "cat log"); put("cwd", "/w"); put("logFile", "/w/l") })
            },
            ev("command_output_started", "t1", ts = CommandFixtures.T) { put("blockId", "b"); put("command", "cat log"); put("logFile", "/w/l") },
            ev("command_output_delta", "t1", ts = CommandFixtures.T) { put("blockId", "b"); put("stream", "stdout"); put("text", "start \u001B]0;") },
            ev("command_output_delta", "t1", ts = CommandFixtures.T) { put("blockId", "b"); put("stream", "stdout"); put("text", "title\nline after\n") },
        )
        val view = commandOutputView(rawBlock(f))!!
        val text = commandPanelText(view.segments).segments.joinToString("") { it.text }
        assertEquals("start 0;title\nline after\n", text)
    }
}
