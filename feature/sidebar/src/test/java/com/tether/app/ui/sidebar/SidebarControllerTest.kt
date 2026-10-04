package com.tether.app.ui.sidebar

import com.tether.app.protocol.ServerMessage
import com.tether.app.ui.prefs.SidebarSort
import com.tether.app.ui.prefs.TetherPreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5.1: the dashboard.tsx / use-tether.ts rules, frame for frame (web payloads cited inline). */
class SidebarControllerTest {
    private val F = SidebarFixtures
    private val client = RecordingClient()
    private var prefs = TetherPreferences.Default
    private val selected = mutableListOf<String>()
    private var now = F.NOW
    private val controller = SidebarController(
        client = client,
        readPreferences = { prefs },
        updatePreferences = { prefs = it(prefs) },
        selectWorkspace = { selected += it },
        clock = { now },
    )

    private fun settings(pinned: List<String>?) = ServerMessage.ServerSettings(
        settings = JsonObject(mapOf("pinnedWorkspaces" to (pinned?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull))),
        envForced = emptyMap(),
        restartRequired = false,
        discovered = emptyList(),
        detected = JsonObject(emptyMap()),
    )

    // ---- v63 seen / unread ------------------------------------------------------------------

    @Test fun openingASessionStampsItSeenAndSendsMarkSeen() {
        // dashboard.tsx:328-338: the stamp is max(Date.now(), seenAt), locally and on the wire.
        controller.markSeen("h1", F.NOW - 5_000)
        assertEquals(listOf(frame("""{"type":"mark-seen","historyId":"h1","seenAt":${F.NOW}}""")), client.frames)
        assertEquals(F.NOW, prefs.lastSeenSessions["h1"])
    }

    @Test fun aStampThatDoesNotAdvanceIsANoOp() {
        prefs = prefs.copy(lastSeenSessions = mapOf("h1" to F.NOW))
        controller.markSeen("h1", F.NOW - 1)
        controller.markSeen(null)
        controller.markSeen("")
        assertTrue(client.frames.isEmpty())
    }

    @Test fun theSettledActiveSessionIsSeenAtItsUpdateTimeButNeverMidTurn() {
        // dashboard.tsx:966-973.
        controller.onActiveSettled(F.live("a", "busy", status = "active", ago = 0, historyId = "h"))
        assertTrue(client.frames.isEmpty())
        now = F.NOW - 60_000 // a device clock behind the broadcast: the broadcast's stamp wins
        controller.onActiveSettled(F.live("a", "done", status = "ready", ago = 0, historyId = "h"))
        assertEquals(listOf(frame("""{"type":"mark-seen","historyId":"h","seenAt":${F.NOW}}""")), client.frames)
        controller.onActiveSettled(F.live("b", "no history", status = "ready", ago = 0))
        assertEquals(1, client.frames.size)
    }

    @Test fun aSeenBroadcastRaisesTheLocalStampAndNeverLowersIt() {
        prefs = prefs.copy(lastSeenSessions = mapOf("h1" to 500L, "h2" to 900L))
        controller.applyRemoteSeen(mapOf("h1" to 700L, "h2" to 100L, "h3" to 50L))
        assertEquals(mapOf("h1" to 700L, "h2" to 900L, "h3" to 50L), prefs.lastSeenSessions)
        assertTrue("remote seen is applied, never re-sent", client.frames.isEmpty())
    }

    // ---- v128 pinned workspaces --------------------------------------------------------------

    @Test fun theServersKeptListIsAdoptedWhenItDiffers() {
        prefs = prefs.copy(pinnedProjects = listOf(F.DOCS))
        controller.syncPinned(settings(listOf(F.APP, F.DOCS)), prefs)
        assertEquals(listOf(F.APP, F.DOCS), prefs.pinnedProjects)
        assertTrue(client.frames.isEmpty())
        assertEquals(listOf(F.APP, F.DOCS), controller.pinnedWorkspaces(settings(listOf(F.APP, F.DOCS)), TetherPreferences.Default))
    }

    @Test fun aServerThatNeverHeldAListIsSeededOnceFromThisDevice() {
        prefs = prefs.copy(pinnedProjects = listOf(F.DOCS))
        controller.syncPinned(null, prefs)
        assertTrue("no settings frame yet: nothing", client.frames.isEmpty())
        controller.syncPinned(settings(null), prefs)
        assertEquals(listOf(frame("""{"type":"set-server-settings","settings":{"pinnedWorkspaces":["${F.DOCS}"]}}""")), client.frames)
        client.frames.clear()
        controller.syncPinned(settings(null), prefs.copy(pinnedProjects = emptyList()))
        assertTrue("an empty device list seeds nothing", client.frames.isEmpty())
    }

    @Test fun pinningAndUnpinningABlockIsWrittenOwnerLevel() {
        controller.togglePinned(F.DOCS, listOf(F.APP))
        controller.togglePinned(F.APP, listOf(F.APP, F.DOCS))
        assertEquals(
            listOf(
                frame("""{"type":"set-server-settings","settings":{"pinnedWorkspaces":["${F.APP}","${F.DOCS}"]}}"""),
                frame("""{"type":"set-server-settings","settings":{"pinnedWorkspaces":["${F.DOCS}"]}}"""),
            ),
            client.frames,
        )
        assertEquals(listOf(F.DOCS), prefs.pinnedProjects)
    }

    @Test fun addWorkspaceKeepsItUnfoldsItAndMakesItCurrent() {
        prefs = prefs.copy(collapsedWorkspaces = listOf(F.DOCS))
        controller.chooseWorkspace(F.DOCS, listOf(F.APP), current = F.ROOT)
        assertEquals(listOf(F.APP, F.DOCS), prefs.pinnedProjects)
        assertTrue(prefs.collapsedWorkspaces.isEmpty())
        assertEquals(listOf(F.DOCS), selected)
        assertEquals(
            listOf(
                frame("""{"type":"set-server-settings","settings":{"pinnedWorkspaces":["${F.APP}","${F.DOCS}"]}}"""),
                frame("""{"type":"discover","cwd":"${F.DOCS}","lastSeen":{},"watch":["${F.DOCS}","${F.APP}"]}"""),
            ),
            client.frames,
        )
    }

    // ---- v93 watched discovery ---------------------------------------------------------------

    @Test fun everyWatchedWorkspaceIsDiscoveredOnConnectWithTheCompleteWatchSet() {
        prefs = prefs.copy(lastSeenSessions = mapOf("h" to 1L))
        controller.watch(connected = false, listOf(F.DOCS, F.ROOT), F.ROOT)
        assertTrue(client.frames.isEmpty())
        controller.watch(connected = true, listOf(F.DOCS, F.ROOT), F.ROOT)
        val watch = """["${F.ROOT}","${F.DOCS}"]"""
        assertEquals(
            listOf(
                frame("""{"type":"discover","cwd":"${F.ROOT}","lastSeen":{"h":1},"watch":$watch}"""),
                frame("""{"type":"discover","cwd":"${F.DOCS}","lastSeen":{"h":1},"watch":$watch}"""),
            ),
            client.frames,
        )
        client.frames.clear()
        controller.watch(connected = true, listOf(F.DOCS, F.ROOT), F.ROOT)
        assertTrue("unchanged set: nothing", client.frames.isEmpty())
        // use-tether.ts watchWorkspaces: an added root is discovered; a removal narrows via the current one.
        controller.watch(connected = true, listOf(F.DOCS, F.APP, F.ROOT), F.ROOT)
        assertEquals(listOf(F.APP), client.frames.map { (it["cwd"] as JsonPrimitive).content })
        client.frames.clear()
        controller.watch(connected = true, listOf(F.ROOT), F.ROOT)
        assertEquals(listOf(frame("""{"type":"discover","cwd":"${F.ROOT}","lastSeen":{"h":1},"watch":["${F.ROOT}"]}""")), client.frames)
    }

    // ---- v67 order + the sidebar's callbacks -------------------------------------------------

    private fun actions(orders: Map<String, List<String>> = emptyMap(), closed: MutableList<String> = mutableListOf()) = controller.actions(
        workspaces = listOf(F.DOCS, F.APP),
        current = F.APP,
        pinned = listOf(F.DOCS, F.APP),
        sessions = F.groupSessions + F.live("h1", "linked", cwd = F.DOCS, ago = 1, historyId = "hist-1"),
        sessionOrders = orders,
        onClose = { closed += "close" },
        onSelect = { closed += "select:$it" },
        onResume = { history -> client.resume(history).also { sent -> if (sent) closed += "opening:${history.historyId}" } },
        onQuery = {},
        onHarness = {},
        onNewSession = { closed += "new" },
        onBrowseWorkspace = {},
        onOpenSettings = {},
    )

    @Test fun dragReorderSendsTheBlocksFullExplicitOrderAndResetSendsEmpty() {
        val a = actions()
        a.onReorderSessions(F.APP, listOf("live:g3", "live:x"))
        a.onResetSessionOrder(F.APP)
        assertEquals(
            listOf(
                frame("""{"type":"set-session-order","cwd":"${F.APP}","order":["live:g3","live:x"]}"""),
                frame("""{"type":"set-session-order","cwd":"${F.APP}","order":[]}"""),
            ),
            client.frames,
        )
    }

    @Test fun choosingASortClearsEveryManualOrder() {
        actions(orders = mapOf(F.APP to listOf("live:g3"), F.DOCS to emptyList())).onSortModeChange(SidebarSort.LastActive)
        assertEquals(SidebarSort.LastActive, prefs.sidebarSort)
        assertEquals(listOf(frame("""{"type":"set-session-order","cwd":"${F.APP}","order":[]}""")), client.frames)
    }

    @Test fun endingARowSendsKillStraightThrough() {
        // dashboard.tsx:1311-1314: the two-tap arm (or the swipe) is the confirmation.
        actions().onEndSession("g2", "https://a.example")
        assertEquals(listOf(frame("""{"type":"kill","sessionId":"g2"}""")), client.frames)
        // r3: bound to the server the row was armed for.
        assertEquals(listOf<String?>("https://a.example"), client.killOrigins)
    }

    @Test fun selectingARowFollowsItsBlockAndMarksItSeen() {
        val log = mutableListOf<String>()
        actions(closed = log).onSelectSession("h1")
        assertEquals(listOf("select:h1"), log)
        assertEquals(listOf(F.DOCS), selected)
        assertEquals(listOf("discover", "mark-seen"), client.types())
        assertEquals(frame("""{"type":"mark-seen","historyId":"hist-1","seenAt":${F.NOW}}"""), client.frames.last())
    }

    @Test fun reopeningAHistoryResumesItMarksItSeenAndClosesTheDrawer() {
        val log = mutableListOf<String>()
        actions(closed = log).onReopenHistory(F.history("hist-9", "old", cwd = F.APP, ago = 60))
        assertEquals(listOf("resume", "mark-seen"), client.types())
        assertEquals(frame("""{"type":"resume","historyId":"hist-9","cwd":"${F.APP}"}"""), client.frames.first())
        assertEquals(listOf("opening:hist-9", "close"), log)
    }

    @Test fun aResumeThatWasNotSentIsNotMarkedSeenAndKeepsTheDrawerOpen() {
        // dashboard.tsx:400 `if (resumeHistory(history))` — the rest only when the frame went out.
        client.resumeSends = false
        val log = mutableListOf<String>()
        actions(closed = log).onReopenHistory(F.history("hist-9", "old", cwd = F.DOCS, ago = 60))
        assertEquals("its block still becomes current", listOf(F.DOCS), selected)
        assertTrue(client.types().toString(), client.types().none { it == "resume" || it == "mark-seen" })
        assertTrue(log.toString(), log.isEmpty())
        assertEquals(null, prefs.lastSeenSessions["hist-9"])
    }

    @Test fun aProfilePinnedHistoryResumesOntoItsProfile() {
        // v89 use-tether.ts:1494: profileId rides along only when the history names one.
        actions().onReopenHistory(F.history("hist-p", "on a profile", cwd = F.APP, ago = 60).copy(profileId = "work"))
        actions().onReopenHistory(F.history("hist-d", "default profile", cwd = F.APP, ago = 60).copy(profileId = ""))
        assertEquals(
            listOf(
                frame("""{"type":"resume","historyId":"hist-p","cwd":"${F.APP}","profileId":"work"}"""),
                frame("""{"type":"resume","historyId":"hist-d","cwd":"${F.APP}"}"""),
            ),
            client.frames.filter { (it["type"] as JsonPrimitive).content == "resume" },
        )
    }

    @Test fun plusOnABlockMakesItCurrentThenOpensTheComposer() {
        val log = mutableListOf<String>()
        actions(closed = log).onNewSessionIn(F.DOCS)
        assertEquals(listOf(F.DOCS), selected)
        assertEquals(listOf("new"), log)
    }

    @Test fun togglesPersistTheWebPreferences() {
        val a = actions()
        a.onToggleActiveOnly(); a.onToggleUnreadOnly(); a.onToggleHideAgentRuns(); a.onToggleWorkspaceCollapsed(F.DOCS)
        a.onCollapse!!.invoke()
        assertEquals(true, prefs.sidebarActiveOnly)
        assertEquals(true, prefs.sidebarUnreadOnly)
        assertEquals(false, prefs.sidebarHideAgentRuns)
        assertEquals(listOf(F.DOCS), prefs.collapsedWorkspaces)
        assertEquals(true, prefs.sidebarCollapsed)
        assertTrue(client.frames.isEmpty())
    }

    @Test fun theCurrentWorkspaceFallsBackLikeTheWeb() {
        assertEquals(F.DOCS, controller.currentWorkspace(F.DOCS, prefs.copy(defaultWorkspace = F.APP), F.ROOT))
        assertEquals(F.APP, controller.currentWorkspace(null, prefs.copy(defaultWorkspace = F.APP), F.ROOT))
        assertEquals(F.ROOT, controller.currentWorkspace(null, prefs, F.ROOT))
    }
}
