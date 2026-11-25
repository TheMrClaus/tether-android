package com.tether.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.tether.app.client.BrowserSocket
import com.tether.app.client.BrowserSocketListener
import com.tether.app.client.BrowserSocketOpen
import com.tether.app.client.BrowserSocketOpener
import com.tether.app.ui.chat.BrowserDockRequest
import com.tether.app.ui.chat.BrowserPaneHost
import com.tether.app.ui.chat.LocalBrowserDock
import com.tether.app.ui.statusline.ContextGauge
import com.tether.app.ui.statusline.SessionDial
import com.tether.app.ui.theme.LocalTetherTokens

/** A `/ws-browser` that opens at once and reports one page, so the pane reads as it does live. */
class DockOpener : BrowserSocketOpener {
    override fun open(sessionId: String, listener: BrowserSocketListener): BrowserSocketOpen {
        val socket = object : BrowserSocket {
            override fun send(text: String) = true
            override fun close() {}
        }
        listener.onOpen(socket)
        listener.onMessage(
            """{"t":"state","url":"https://localhost:3000/","title":"Dev server","viewport":{"width":390,"height":844,"mobile":true,"deviceScaleFactor":1},"pickMode":false,"loading":false}""",
        )
        return BrowserSocketOpen.Opened(socket)
    }
}

const val DockChatList = "dock-chat-list"
const val DockChatField = "dock-chat-field"

/** What the test drives: whether the pane is open, the chat's scroll and its text field. */
class DockHarness {
    var open by mutableStateOf(false)
    var list: LazyListState? = null
    var closes = 0
}

/**
 * The expanded slots with a chat stand-in that does what the chat screen does with its pane: ask the
 * shell's dock for the column when there is one, draw the overlay itself when there is not.
 */
fun dockSlots(h: DockHarness): PhoneShellSlots = PhoneShellSlots(
    drawer = { Box(Modifier.fillMaxSize().testTag(DrawerSlotTag)) },
    chat = {
        val t = LocalTetherTokens.current
        val dock = LocalBrowserDock.current
        val listState = remember { LazyListState() }
        SideEffect { h.list = listState }
        Box(Modifier.fillMaxSize().background(t.graphite).testTag(ChatSlotTag)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(DockChatList)) {
                items((0 until 200).toList()) { i -> Box(Modifier.fillMaxWidth().height(40.dp)) }
            }
            BasicTextField(
                value = remember { mutableStateOf(TextFieldValue("")) }.let { s -> s.value },
                onValueChange = {},
                modifier = Modifier.align(Alignment.BottomStart).height(40.dp).fillMaxWidth().testTag(DockChatField),
            )
            if (h.open) {
                if (dock != null) {
                    SideEffect { dock.request = BrowserDockRequest(DockOpener(), "s1", { h.open = false; h.closes++ }, { _, _ -> }, null) }
                    androidx.compose.runtime.DisposableEffect(dock) { onDispose { dock.request = null } }
                } else {
                    BrowserPaneHost(DockOpener(), "s1", { h.open = false; h.closes++ }, Modifier.align(Alignment.TopEnd))
                }
            }
        }
    },
    inspector = { Box(Modifier.testTag(InspectorSlotTag)) },
    gauge = { host -> ContextGauge(null, showLabel = host.showLabel, pressed = host.open, onClick = host.onToggle, env = ExpandedFixtures.env) },
    dial = { s -> SessionDial(s.startedAt, s.endedAt, active = s.status != "exited", clock = { ExpandedFixtures.Now }) },
)
