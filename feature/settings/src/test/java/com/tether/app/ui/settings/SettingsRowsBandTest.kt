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
