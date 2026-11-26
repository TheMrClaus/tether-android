package com.tether.app.ui.draft

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.DraftComposerModel
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.chat.ProposalActions
import com.tether.app.ui.chat.ProposalCard
import com.tether.app.ui.chat.LocalProposalActions
import com.tether.app.ui.chat.ProposalOptionsHost
import com.tether.app.ui.chat.ProposalOptionsPanelChrome
import com.tether.app.ui.chat.ProposalPlacement
import com.tether.app.ui.chat.ProposalReplacedView
import com.tether.app.ui.chat.ProposalRow
import com.tether.app.ui.chat.ProposalState
import com.tether.app.ui.chat.ProposalView
import com.tether.app.ui.prefs.InMemoryDraftStore
import com.tether.app.ui.shell.choiceFor
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The proposal card's Options panel in place: the card at the top of the list (the panel opens below it), at the bottom
 * (it flips above), with a replaced suggestion, a refused row, and the danger tone on Mode. The panel is drawn inline, at the
 * place the card's position provider picks, because a popup window is not part of a node capture.
 */
enum class ProposalOptionsShot(val id: String, val atBottom: Boolean, val replaced: Boolean = false, val refused: Boolean = false, val danger: Boolean = false, val reasonix: Boolean = false) {
    First("proposal-options-first", atBottom = false),
    Last("proposal-options-last", atBottom = true),
    Replaced("proposal-options-replaced", atBottom = false, replaced = true, danger = true),
    Refused("proposal-options-refused", atBottom = false, refused = true),
    Reasonix("proposal-options-reasonix", atBottom = false, reasonix = true),
}

private fun exact() = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

internal class ProposalRowsSeed(shot: ProposalOptionsShot) {
    private val job = Job()
    val client = DraftTestClient(failOnSend = false).apply {
        providerCatalog.value = DraftFixtures.catalog
        providers.value = DraftFixtures.providers
        if (shot.reasonix) {
            providerCatalog.value = DraftFixtures.catalog + com.tether.app.client.ProviderCatalogEntry(
                "reasonix", "reasonix", "ready", listOf(com.tether.app.protocol.SessionModelOption("m1", "Reasonix")), label = "Reasonix",
            )
        }
    }
    private val model = DraftComposerModel(
        client = client, draftStore = InMemoryDraftStore(), scope = CoroutineScope(Dispatchers.Unconfined + job),
        currentWorkspace = { null }, newRequestId = { "req-1" }, persistPicks = false,
    )

    init {
        model.onOrigin(DraftFixtures.ORIGIN)
        model.refresh()
        if (shot.reasonix) model.selectProviderAndModel("reasonix", "m1") else model.selectProviderAndModel("claude", "m2")
        model.setCwd("/srv/ws/parity-app")
        if (shot.danger) model.selectMode("bypassPermissions", "claude")
    }

    val state = model.state.value
    val seedForm = state.form

    fun inputs(shot: ProposalOptionsShot, replaced: List<ProposalReplacedView>): ProposalRowsInputs {
        val catalog = client.providerCatalog.value
        val browser = draftBrowserInputs(state, catalog, client.providers.value, com.tether.app.ui.chat.IcuJsCollator.forLocale(java.util.Locale.US), 1_700_000_000_000L)
        return ProposalRowsInputs(
            draft = state,
            seedForm = seedForm,
            suggested = if (shot.replaced) setOf(ProposalRow.Folder) else setOf(ProposalRow.Folder, ProposalRow.Model),
            replaced = replaced,
            faultRow = if (shot.refused) ProposalRow.Folder else null,
            enabled = true,
            browser = browser,
            browserOpen = false,
            quickPicks = workspaceQuickPicks(listOf("/srv/ws/parity-app"), "", DraftFixtures.ROOT, DraftFixtures.ROOT),
            workspaceRoot = DraftFixtures.ROOT,
            providerLabel = if (shot.reasonix) "Reasonix" else "Claude",
            accountLabel = "Default",
            accountChoosable = !shot.reasonix,
        )
    }

    fun close() = job.cancel()
}

private const val PROMPT = "Split the parser into a tokenizer and a grammar module, keep the public entry point as it is, and move the fixtures next to the module that uses them."

@Composable
private fun ProposalInPlace(shot: ProposalOptionsShot, seed: ProposalRowsSeed, wellHeight: Dp, wellWidth: Dp) {
    val t = LocalTetherTokens.current
    val density = LocalDensity.current
    val replaced = if (shot.replaced) listOf(ProposalReplacedView("model", "invalid_settings", "opus-9")) else emptyList()
    val view = ProposalView(
        proposalId = "p1", title = "Split the parser into a tokenizer and a grammar module", prompt = PROMPT,
        suggestions = if (shot.replaced) JsObj.of("cwd" to JsStr("/srv/ws/parity-app")) else JsObj.of("cwd" to JsStr("/srv/ws/parity-app"), "model" to JsStr("m2")),
        replaced = replaced, state = if (shot.refused) ProposalState.Refused else ProposalState.Pending,
        refusalCode = if (shot.refused) "folder_unavailable" else null, startedSessionId = null, startedSessionName = null,
    )
    var card by remember { mutableStateOf<IntRect?>(null) }
    var natural by remember { mutableStateOf(0) }
    val bounds = with(density) { IntRect(0, 0, wellWidth.roundToPx(), wellHeight.roundToPx()) }
    val gap = with(density) { 4.dp.roundToPx() }
    val inset = with(density) { 8.dp.roundToPx() }
    val local = if (shot.refused) {
        mapOf("p1" to com.tether.app.client.ProposalLocalState("start", "r1", com.tether.app.client.ProposalRefusal("folder_unavailable", com.tether.app.client.Proposals.REFUSAL_TEXTS.getValue("folder_unavailable"))))
    } else emptyMap()
    val actions = ProposalActions(sessionId = "s1", local = local, optionsHost = ProposalOptionsHost { }, cardShown = true)
    Box(Modifier.width(wellWidth).height(wellHeight).background(t.graphite).testTag("proposal-well")) {
        CompositionLocalProvider(LocalProposalActions provides actions) {
            Box(
                Modifier.align(if (shot.atBottom) Alignment.BottomCenter else Alignment.TopCenter).padding(12.dp).fillMaxWidth()
                    .onGloballyPositioned { c -> c.boundsInWindow().let { card = IntRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) } },
            ) { ProposalCard(view) }
        }
        val c = card
        if (c != null) {
            val cap = ProposalPlacement.capHeight(c, bounds, gap, with(density) { 96.dp.roundToPx() })
            val y = ProposalPlacement.place(c, natural.coerceAtMost(cap), bounds, gap).coerceAtLeast(bounds.top)
            val x = (c.left + inset).coerceAtLeast(bounds.left)
            ProposalOptionsPanelChrome(with(density) { cap.toDp() }, Modifier.onSizeChanged { natural = it.height }.offset { IntOffset(x, y) }.width(wellWidth - 40.dp)) {
                ProposalOptionRowsFrame(seed.inputs(shot, replaced), ProposalRowsActions())
            }
        }
    }
}

fun ComposeContentTestRule.snapProposalOptions(shot: ProposalOptionsShot, skin: TetherSkin, size: String, wellHeight: Dp, wellWidth: Dp) {
    mainClock.autoAdvance = false
    val seed = ProposalRowsSeed(shot)
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides true) { ProposalInPlace(shot, seed, wellHeight, wellWidth) }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    // The panel is open: Options reads its expanded state (the real panel is a popup window, which a node capture does not hold).
    onNodeWithTag("proposal-options").performClick()
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onNodeWithTag("proposal-well").captureRoboImage("src/test/screenshots/${shot.id}/${skin.id}-$size.png", roborazziOptions = exact())
    seed.close()
}

private val studioSkins = listOf(TetherSkin.StudioDark, TetherSkin.Studio)

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProposalOptionsPhoneScreenshotTest(private val shot: ProposalOptionsShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun panel() = rule.snapProposalOptions(shot, skin, "phone", 678.dp, 412.dp)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ProposalOptionsShot.entries.flatMap { s -> studioSkins.filter { !s.reasonix || it == TetherSkin.Studio }.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ProposalOptionsTabletScreenshotTest(private val shot: ProposalOptionsShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun panel() = rule.snapProposalOptions(shot, skin, "tablet", 530.dp, 950.dp)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ProposalOptionsShot.entries.filter { !it.reasonix }.flatMap { s -> studioSkins.map { arrayOf<Any>(s, it) } }
    }
}
