package com.tether.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.dp
import com.tether.app.client.EngineCard
import com.tether.app.client.SessionMethod
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-smj8 (W22): the settings rows at the web's two row breakpoints, through SettingsFrame with the production defaults
 * (the width source decides, nothing is passed): 35rem (560 dp, globals.css 3217: the plain rows stack, studio.css
 * 985-987) and 640px (studio.css 957: the server rows stack, the device rows wrap). At the web at tether 29537e0:
 * plain rows and selects are stacked at 560 and beside their text from 561; server rows stack through 640 and sit
 * beside from 641; the toggles never stack; `.settings-device` rows keep their keys beside the text from 561 only
 * while text, gap and keys fit one line, and stack them under the text at their own width otherwise (never full width
 * above 560).
 */
abstract class SettingsRowsBandBase(private val width: Int) {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val a = width <= 560
    private val b = width in 561..640
    private val narrow = width <= 640

    private class R(val l: Float, val t: Float, val w: Float, val h: Float) {
        val r get() = l + w
        val b get() = t + h
        override fun toString() = "[$l,$t ${w}x$h]"
    }

    private fun SemanticsNodeInteraction.rect(): R {
        val n = fetchSemanticsNode()
        val p = n.positionInRoot
        return R(p.x, p.y, n.size.width.toFloat(), n.size.height.toFloat())
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true).rect()
    private fun text(t: String) = compose.onAllNodesWithText(t, useUnmergedTree = true).onFirst().rect()

    private fun show(
        tab: SettingsTab,
        server: ServerSettingsBinding = ServerSettingsBinding.None,
        nodes: List<com.tether.app.protocol.NodeSummary>? = null,
        devices: DevicesSeed? = null,
        accounts: AccountsShot? = null,
    ) {
        val stored = runBlocking { store.prefs.preferences.first() }
        val state = SettingsDialogState(tab, GeneralDraft.of(stored))
        compose.setContent {
            val actions = nodes?.let { rememberNodesActions(NeverWritesNodes, null) }
            val controller = devices?.let { rememberDevicesController(NeverCalledSecurity, DevicesFixtures.ORIGIN, it, authenticator = NeverPromptsPasskeys) }
            TetherTheme(TetherSkin.Studio.mode) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SettingsFrame(
                        prefs = store.prefs,
                        state = state,
                        restartRequired = false,
                        currentWorkspace = CURRENT,
                        onClose = {},
                        // The shell's own switch mirrors settingsLayout(); the row switch is left to its default.
                        layout = if (narrow) TetherLayoutClass.Phone else TetherLayoutClass.Expanded,
                        initialPreferences = stored,
                        serverSettings = server,
                        claudeAccounts = accounts?.binding() ?: ClaudeAccountsBinding.None,
                        nodes = if (nodes != null && actions != null) NodesBinding(nodes, NodeFixtures.ORIGIN, actions, NodeFixtures.CONSOLE, now = { NodeFixtures.NOW }) else NodesBinding.None,
                        devices = controller?.let { DevicesBinding(it, now = { DevicesFixtures.NOW }) } ?: DevicesBinding.None,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The row's content width: the body less the section's inline padding (20 at 640 and under, 28 above). */
    private fun rowWidth(): Float = tag(SettingsDialogTags.Body).w - 2 * (if (narrow) 20f else 28f)

    private fun assertBelow(what: String, title: R, key: R) {
        assertTrue("$what: key $key is under its text $title", key.t >= title.b - 0.5f)
        assertEquals("$what: key starts at the text's left edge", title.l, key.l, 1f)
    }

    private fun assertBeside(what: String, title: R, key: R) {
        assertTrue("$what: key $key is beside its text $title", key.l >= title.r - 0.5f)
        assertEquals("$what: the key ends at the row's edge", title.l + rowWidth(), key.r, 1f)
    }

    @Test fun defaultWorkspaceStacksOnlyAtOrBelow560() {
        show(SettingsTab.General)
        val title = text("Default workspace")
        val key = tag(SettingsPanelTags.UseCurrent)
        if (a) {
            assertBelow("560", title, key)
            assertEquals("the key fills the row", rowWidth(), key.w, 1f)
        } else {
            assertBeside("$width", title, key)
            assertTrue("the key keeps its own width (${key.w})", key.w < rowWidth() / 2f)
        }
    }

    @Test fun theTogglesNeverStack() {
        show(SettingsTab.General)
        val toggle = GeneralToggle.entries.first()
        val row = tag(SettingsPanelTags.toggle(toggle))
        val title = text(toggle.title)
        // The 40 dp switch sits beside the text (plus the gap): the text never reaches the row's right edge.
        assertTrue("the switch is beside the text: row $row, title $title", row.r - title.r >= 40f + 16f - 1f)
    }

    @Test fun aServerRowStacksThrough640() {
        show(SettingsTab.Engines, server = ServerShot.EnginesMissing.binding())
        val title = text(EngineRows.DETECTED_HOME)
        val key = tag(EngineTags.useDetected(EngineCard.Opencode))
        if (narrow) {
            assertBelow("$width", title, key)
            if (a) {
                val card = tag(EngineTags.card(EngineCard.Opencode))
                assertTrue("560: the key fills the row (${key.w} of card ${card.w})", key.w > card.w - 80f)
            } else {
                assertTrue("$width: the key keeps its own width (${key.w})", key.w < 200f)
            }
        } else {
            assertTrue("641: beside the text", key.l >= title.r - 0.5f)
        }
    }

    @Test fun theCliSelectIsCappedAtItsShareOfTheRow() {
        show(SettingsTab.Advanced, server = ServerShot.Cli.binding())
        val title = text(ClaudeCliCopy.PICKER_TITLE)
        val select = tag(ServerSettingsTags.CliPicker)
        val row = rowWidth()
        if (a) {
            assertBelow("560", title, select)
            assertEquals("52% of the row at 560", row * 0.52f, select.w, 1f)
        } else {
            assertTrue("$width: beside the text (select $select, title $title)", select.l >= title.r - 0.5f)
            val cap = row * (if (b) 0.52f else 0.50f)
            assertTrue("$width: ${select.w} is at most ${cap}", select.w <= cap + 1f)
            assertTrue("$width: intrinsic, not filled to its cap (${select.w} of $cap)", select.w < cap - 4f)
        }
    }

    /** CSS `max-width: 52%` is of the flex container's content width, never of what is left after the 16 dp gap. */
    @Test fun theShareIsOfTheWholeRow() {
        compose.setContent {
            Box(Modifier.width(497.dp)) {
                Row {
                    Box(Modifier.weight(1f).height(10.dp))
                    Box(Modifier.maxWidthFraction(0.52f).width(400.dp).height(10.dp).testTag("capped"))
                }
            }
        }
        // The weighted text is skipped in the first pass, so the key is measured against the whole 497: 52% is 258.4 (not 253.2, 52% of 487).
        assertEquals(258f, compose.onNodeWithTag("capped").rect().w, 1f)
    }

    @Test fun thePasskeyAddFieldAndKeyStackOnlyAtOrBelow560() {
        show(SettingsTab.Devices, devices = DevicesFixtures.seed())
        val field = tag(DevicesTags.PasskeyLabel)
        val add = tag(DevicesTags.AddPasskey)
        if (a) {
            assertTrue("560: the key is under the field", add.t >= field.b - 0.5f)
            assertEquals("560: both fill the row", field.w, add.w, 1f)
            assertEquals("560: the key fills the row", rowWidth(), add.w, 1f)
        } else {
            assertTrue("$width: one line (field $field, key $add)", add.l >= field.r - 0.5f && add.t < field.b)
        }
    }

    @Test fun thePasskeyAndNodeKeysStackOnlyAtOrBelow560() {
        show(SettingsTab.Devices, devices = DevicesFixtures.seed())
        val rename = tag(DevicesTags.rename(DevicesFixtures.LAPTOP_KEY.id))
        val remove = tag(DevicesTags.remove(DevicesFixtures.LAPTOP_KEY.id))
        assertKeys(rename, remove, "passkey")
    }

    @Test fun theNodeKeysStackOnlyAtOrBelow560() {
        show(SettingsTab.Nodes, nodes = listOf(NodeFixtures.WORKSTATION))
        assertKeys(tag(NodeTags.probe("node_ws")), tag(NodeTags.remove("node_ws")), "node")
    }

    private fun assertKeys(first: R, second: R, what: String) {
        if (a) {
            assertTrue("560: $what keys stacked ($first, $second)", second.t >= first.b - 0.5f)
            assertEquals("560: $what keys are the same width", first.w, second.w, 1f)
        } else {
            assertTrue("$width: $what keys side by side ($first, $second)", second.l >= first.r - 0.5f && Math.abs(second.t - first.t) < 2f)
        }
    }

    /** The distance from the lowest text above [key] (the row's caption) to [key]: the stacked row's gap. */
    private fun gapAbove(key: R): Float {
        val d = compose.density.density
        val above = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { n -> val p = n.positionInRoot; R(p.x / d, p.y / d, n.size.width / d, n.size.height / d) }
            .filter { it.b <= key.t + 0.5f && it.b > key.t - 80f && it.l < key.r && it.r > key.l }
        return key.t - above.maxOf { it.b }
    }

    /** ta-bnnz C1 (studio.css 975): a stacked server row sits 12 under its text; a plain row 16. */
    @Test fun aStackedServerRowIs12UnderItsTextAndAPlainRow16() {
        show(SettingsTab.Engines, server = ServerShot.EnginesMissing.binding())
        val key = tag(EngineTags.useDetected(EngineCard.Opencode))
        if (narrow) assertEquals("$width: server row gap", 12f, gapAbove(key), 0.5f)
        else assertTrue("641: beside the text", key.l >= text(EngineRows.DETECTED_HOME).r - 0.5f)
    }

    @Test fun aPlainRowStaysAt16() {
        show(SettingsTab.General)
        if (a) assertEquals("560: plain row gap", 16f, gapAbove(tag(SettingsPanelTags.UseCurrent)), 0.5f)
    }

    /** ta-bnnz C2: at 560 and under a direct-child key of an account's server row spans the row. */
    @Test fun theTerminalAliasKeyFillsTheRowAtOrBelow560() {
        show(SettingsTab.Engines, accounts = AccountsShot.Loaded)
        val show = tag(ClaudeAccountsTags.alias("claude-work"))
        val card = tag(ClaudeAccountsTags.card("claude-work"))
        val content = card.w - 2 * (if (narrow) 16f else 20f)
        if (a) assertEquals("560: Show fills the row", content, show.w, 1f)
        else assertTrue("$width: Show keeps its own width (${show.w} of $content)", show.w < content / 2f)
        val logIn = ClaudeAccountsTags.logout("claude-work")
        assertTrue("$width: Log out keeps its own width", tag(logIn).w < content / 2f)
    }

    /** ta-bnnz C3: the sync selects are 52% of the row at 560 and under (studio.css 974, globals.css 3223). */
    @Test fun theSyncSelectsAre52PercentOfTheRowAtOrBelow560() {
        show(SettingsTab.Engines, accounts = AccountsShot.Sync)
        for (id in listOf(ClaudeAccountsTags.SyncMode, ClaudeAccountsTags.SyncPrimary)) {
            val select = tag(id)
            if (a) assertEquals("560: $id is 0.52 of the row", rowWidth() * 0.52f, select.w, 1f)
            else assertTrue("$width: $id is at most 0.52 of the row (${select.w})", select.w <= rowWidth() * 0.52f + 1f)
        }
        if (narrow) assertEquals("$width: the mode select is 12 under its text", 12f, gapAbove(tag(ClaudeAccountsTags.SyncMode)), 0.5f)
    }

    /** ta-bnnz F2: the passkey label field and "Add a passkey" are 12 apart at 560 and under (sign-in-security.tsx:184). */
    @Test fun thePasskeyFieldAndKeyAre12ApartAtOrBelow560() {
        show(SettingsTab.Devices, devices = DevicesFixtures.seed())
        if (a) assertEquals("560", 12f, tag(DevicesTags.AddPasskey).t - tag(DevicesTags.PasskeyLabel).b, 0.5f)
    }

    /** ta-bnnz C2 (Devices): "Sign out everywhere else" and "Pair a device" fill the row at 560 and under. */
    @Test fun theDevicesKeysFillTheRowAtOrBelow560() {
        show(SettingsTab.Devices, devices = DevicesFixtures.seed())
        val out = tag(DevicesTags.SignOutOthers)
        val pair = tag(DevicesTags.Pair)
        if (a) {
            assertEquals("560: Sign out everywhere else fills the row", rowWidth(), out.w, 1f)
            assertEquals("560: Pair a device fills the row", rowWidth(), pair.w, 1f)
        } else {
            assertTrue("$width: Sign out everywhere else keeps its own width (${out.w})", out.w < rowWidth() / 2f)
            assertTrue("$width: Pair a device keeps its own width (${pair.w})", pair.w < rowWidth() / 2f)
        }
    }

    /** ta-bnnz F3 (globals.css 8711-8723): the hint is under "Pair a device" at 560 (12 below), beside it (12) otherwise. */
    @Test fun thePairHintIsUnderTheKeyAt560AndBesideFrom561() {
        show(SettingsTab.Devices, devices = DevicesFixtures.seed())
        val pair = tag(DevicesTags.Pair)
        val hint = tag(DevicesTags.PairHint)
        if (a) {
            assertEquals("560: the hint is 12 under the key", 12f, hint.t - pair.b, 0.5f)
            assertEquals("560: starting at its left edge", pair.l, hint.l, 1f)
        } else {
            assertEquals("$width: the hint is 12 beside the key", 12f, hint.l - pair.r, 0.5f)
            assertTrue("$width: on the key's line ($pair, $hint)", hint.t < pair.b)
        }
    }

    /** ta-bnnz C7a: at 560 and under "Copy code" is the row's width and the expiry wraps 12 under it. */
    @Test fun theCodeActionsWrapAtOrBelow560() {
        show(SettingsTab.Devices, devices = DevicesShot.Code.seed())
        val copy = tag(DevicesTags.CodeCopy)
        val expiry = tag(DevicesTags.CodeExpiry)
        if (a) {
            assertEquals("560: Copy code fills the code card's row", tag(DevicesTags.CodeCard).w - 32f, copy.w, 1f)
            assertEquals("560: the expiry is 12 under it", 12f, expiry.t - copy.b, 0.5f)
        } else {
            assertEquals("$width: the expiry is 12 beside it", 12f, expiry.l - copy.r, 0.5f)
        }
    }

    /** studio.css 985: a long text sends the keys to their own line at 561-640; at 641 they stay beside it. */
    @Test fun aLongSessionLineDropsItsKeyOnlyAtOrBelow640() {
        // The row cuts a user agent at 64 characters; wide ones keep the unwrapped line past the 561 threshold.
        val long = DevicesFixtures.BROWSER.copy(userAgent = "WWWWWWWWWW".repeat(7))
        show(SettingsTab.Devices, devices = DevicesFixtures.seed(sessions = listOf(long)))
        val title = text(DevicesCopy.method(SessionMethod.Password))
        val key = tag(DevicesTags.signOut(long.id))
        if (narrow) {
            assertBelow("$width", title, key)
            if (b) assertTrue("$width: the key keeps its own width (${key.w})", key.w < rowWidth() / 2f)
        } else {
            assertTrue("641: beside the text (key $key, title $title)", key.l >= title.r - 0.5f)
        }
    }

    @Test fun aLongNodeLineDropsItsKeysOnlyAtOrBelow640() {
        val long = NodeFixtures.node(
            "node_long", "Long node", "http://a-very-long-hostname-for-a-lab-machine.internal.example.test:4173/console/base/path",
            "reachable", NodeFixtures.NOW - 5 * 60_000, "0.14.2", 137,
        )
        show(SettingsTab.Nodes, nodes = listOf(long))
        val title = text("Long node")
        val probe = tag(NodeTags.probe("node_long"))
        if (narrow) {
            assertBelow("$width", title, probe)
            if (b) assertTrue("$width: the keys keep their own width (${probe.w})", probe.w < rowWidth() / 2f)
        } else {
            assertTrue("641: beside the text (key $probe, title $title)", probe.l >= title.r - 0.5f)
        }
    }

    @Test fun aShortNodeLineKeepsItsKeysBesideFrom561() {
        show(SettingsTab.Nodes, nodes = listOf(NodeFixtures.node("node_s", "Home", "http://10.0.0.2:4173", "reachable")))
        val title = text("Home")
        val probe = tag(NodeTags.probe("node_s"))
        if (a) assertBelow("560", title, probe)
        else assertTrue("$width: beside the text (key $probe, title $title)", probe.l >= title.r - 0.5f)
    }
}

@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w560dp-h900dp-mdpi") class SettingsRowsBand560Test : SettingsRowsBandBase(560)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w561dp-h900dp-mdpi") class SettingsRowsBand561Test : SettingsRowsBandBase(561)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w640dp-h900dp-mdpi") class SettingsRowsBand640Test : SettingsRowsBandBase(640)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w641dp-h900dp-mdpi") class SettingsRowsBand641Test : SettingsRowsBandBase(641)
