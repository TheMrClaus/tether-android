package com.tether.app.ui.inspector

import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.ui.inspector.InspectorBoards.ORIGIN
import com.tether.app.ui.inspector.InspectorBoards.obj
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T15.7 / ta-coik.2: a worktree service row's "Open" and "On this machine" links and "not
 * configured" text, mapped from the v134 snapshot (worktree-services-card.tsx 140-160). Every control character in this file is written
 * as an escape.
 */
class ServiceLinkModelTest {
    private val notConfigured = "Own address not configured — set TETHER_SERVICE_ORIGIN (see docs/worktrees.md)."
    private val tooLong = "No own address: its hostname would exceed 63 characters. Shorten the script name or slug."
    private val invalid = "No own address: its script name or worktree slug is not a valid hostname part."
    private val link = "/api/worktree/open?session=s1&script=web"

    /** One script (`fields` is the JSON after its common head), mapped for session s1 on [ORIGIN]. */
    private fun row(fields: String, origin: String? = ORIGIN, sessionId: String = "s1"): ServiceRow {
        val json = """{"sessionId":"s1","setupStatus":"ok","setupLog":[],"configWarnings":[],"scripts":[{"command":"npm run dev"$fields}]}"""
        return services(obj(json), sessionId, origin)!!.scripts.single()
    }

    private fun running(extra: String) = row(""","name":"web","type":"service","status":"running"$extra""")

    @Test fun aPinnedLinkOpensAndShowsTheServiceHost() {
        val r = running(""","proxyHost":"web--feat.svc.example.test","proxyUrl":"https://web--feat.svc.example.test","proxyPath":null,"proxyAuthUrl":"$link","proxyUnavailable":null""")
        val open = r.open!!
        assertEquals("$ORIGIN$link", open.url)
        assertEquals("web--feat.svc.example.test", open.host.text)
        assertEquals(Rule.Line, open.host.rule)
        assertEquals(open.host, r.address)
        assertNull(r.unavailable)
        // Pre-v134 servers sent no proxyUnavailable at all: same link.
        assertNotNull(running(""","proxyHost":"h.example.test","proxyAuthUrl":"$link"""").open)
    }

    @Test fun eachUnavailableReasonHasTheWebsText() {
        val cases = mapOf(
            "\"not-configured\"" to notConfigured,
            "\"label-too-long\"" to tooLong,
            "\"label-invalid\"" to invalid,
            // lib/protocol.ts: an unknown value reads like "not-configured"; so does a wrong type.
            "\"future-reason\"" to notConfigured,
            "3" to notConfigured,
            "{\"x\":1}" to notConfigured,
            "null" to notConfigured,
        )
        for ((value, copy) in cases) {
            val r = running(""","proxyHost":null,"proxyUrl":null,"proxyPath":null,"proxyAuthUrl":null,"proxyUnavailable":$value""")
            assertEquals(value, copy, r.unavailable)
            assertNull(value, r.open)
            assertNull(value, r.address)
        }
        // Absent fields (an older server): the fallback too.
        assertEquals(notConfigured, running("").unavailable)
    }

    @Test fun aReasonWinsOverALinkTheServerShouldNotHaveSent() {
        val r = running(""","proxyHost":"h.example.test","proxyAuthUrl":"$link","proxyUnavailable":"label-invalid"""")
        assertNull(r.open)
        assertNull(r.address)
        assertEquals(invalid, r.unavailable)
        // A wrongly typed reason fails closed too.
        assertNull(running(""","proxyHost":"h.example.test","proxyAuthUrl":"$link","proxyUnavailable":false""").open)
    }

    @Test fun nullAbsentOrWronglyTypedLinkFieldsAreNoLink() {
        for (fields in listOf(
            ""","proxyHost":"h.example.test"""",
            ""","proxyHost":"h.example.test","proxyAuthUrl":null""",
            ""","proxyHost":"h.example.test","proxyAuthUrl":""""",
            ""","proxyHost":"h.example.test","proxyAuthUrl":7""",
            ""","proxyHost":"h.example.test","proxyAuthUrl":{"x":1}""",
            ""","proxyHost":"h.example.test","proxyAuthUrl":["$link"]""",
            // The sheet must name the service: no host, no link.
            ""","proxyAuthUrl":"$link"""",
            ""","proxyHost":null,"proxyAuthUrl":"$link"""",
            ""","proxyHost":"","proxyAuthUrl":"$link"""",
            ""","proxyHost":[],"proxyAuthUrl":"$link"""",
        )) {
            val r = running(fields)
            assertNull(fields, r.open)
            assertNull(fields, r.address)
            assertEquals(fields, notConfigured, r.unavailable)
        }
    }

    @Test fun hostileProxyAuthUrlsAreNoLinkAndSayNotConfigured() {
        val huge = "/api/worktree/open?session=s1&script=web&pad=" + "a".repeat(100_000)
        for (raw in listOf(
            "javascript:alert(document.cookie)",
            "file:///data/data/com.tether.app/shared_prefs/x.xml",
            "intent://x#Intent;scheme=https;package=com.evil;end",
            "https://console.example.test@evil.example/api/worktree/open?session=s1&script=web",
            "//console.example.test@evil.example/api/worktree/open?session=s1&script=web",
            "https://console.example.test/api/worktree/open?session=s1&script=web",
            "$ORIGIN/api/worktree/open?session=s1&script=web",
            "//evil.example/api/worktree/open?session=s1&script=web",
            "/\\evil.example/api/worktree/open?session=s1&script=web",
            "/api/worktree/open?session=s1&script=web\u202Egnp.exe",
            "/api/worktree/open?session=s2&script=web",
            huge,
        )) {
            val r = running(""","proxyHost":"web.svc.example.test","proxyAuthUrl":${JsonPrimitive(raw)}""")
            assertNull(raw.take(80), r.open)
            assertEquals(raw.take(80), notConfigured, r.unavailable)
        }
    }

    @Test fun noPairedOriginOrNoSessionIsNoLink() {
        assertNull(row(""","name":"web","type":"service","status":"running","proxyHost":"h.example.test","proxyAuthUrl":"$link"""", origin = null).open)
        assertNull(services(obj("""{"scripts":[{"name":"web","type":"service","status":"running","proxyHost":"h","proxyAuthUrl":"$link"}]}"""))!!.scripts.single().open)
    }

    @Test fun theLinkIsForTheSessionTheInspectorShows() {
        val replies = InspectorReplies(
            worktreeScripts = obj(
                """{"sessionId":"s1","setupStatus":"ok","setupLog":[],"configWarnings":[],"scripts":[
                   {"name":"web","type":"service","command":"x","status":"running","proxyHost":"h.example.test","proxyAuthUrl":"$link"}]}""",
            ),
        )
        val worktree = WorktreeInfo(path = "/w", branch = "b", status = "active")
        val shown = InspectorBoards.model(InspectorBoards.session(worktree = worktree), replies = replies)
        assertEquals("$ORIGIN$link", shown.services!!.scripts.single().open!!.url)
        // The same snapshot read for another session (a stale reply): no link.
        val other = InspectorBoards.session(worktree = worktree).copy(id = "s2")
        assertNull(InspectorBoards.model(other, replies = replies).services!!.scripts.single().open)
        // No paired server: no link.
        assertNull(InspectorBoards.model(InspectorBoards.session(worktree = worktree), replies = replies, serverOrigin = null).services!!.scripts.single().open)
    }

    @Test fun onlyARunningServiceHasALinkOrTheText() {
        val fields = ""","proxyHost":"h.example.test","proxyAuthUrl":"$link""""
        for (status in listOf("starting", "stopping", "exited", "failed", "idle")) {
            val r = row(""","name":"web","type":"service","status":"$status"$fields""")
            assertNull(status, r.open)
            assertNull(status, r.unavailable)
        }
        val script = row(""","name":"web","type":"script","status":"running"$fields""")
        assertNull(script.open)
        assertNull(script.unavailable)
    }

    @Test fun onThisMachineIsOfferedWhenTheServerSendsThePathForm() {
        // worktree-services-card.tsx:148: a loopback console gets proxyPath; shown beside the reason when it has no own address.
        val alone = running(""","proxyHost":null,"proxyUrl":null,"proxyPath":"/services/s1/web/","proxyAuthUrl":null,"proxyUnavailable":"not-configured"""")
        assertNull(alone.open)
        assertEquals("$ORIGIN/services/s1/web/", alone.local!!.url)
        assertEquals(notConfigured, alone.unavailable)
        // With an own address: both links, each its own route.
        val both = running(""","proxyHost":"h.example.test","proxyPath":"/services/s1/web/","proxyAuthUrl":"$link"""")
        assertEquals("$ORIGIN$link", both.open!!.url)
        assertEquals("$ORIGIN/services/s1/web/", both.local!!.url)
        // A remote viewer is sent no path form: no link.
        assertNull(running(""","proxyHost":"h.example.test","proxyPath":null,"proxyAuthUrl":"$link"""").local)
        // Only for a running service.
        assertNull(row(""","name":"web","type":"service","status":"exited","proxyPath":"/services/s1/web/"""").local)
        // Another session's path form: no link.
        assertNull(running(""","proxyPath":"/services/s2/web/"""").local)
        assertFalse("never printed", "/services/" in alone.toString())
    }

    @Test fun theModelNeverPrintsTheLink() {
        val r = running(""","proxyHost":"h.example.test","proxyAuthUrl":"$link"""")
        assertNotNull(r.open)
        for (text in listOf(r.toString(), r.open.toString())) {
            assertFalse(text, "worktree/open" in text)
            assertFalse(text, "console.example.test" in text)
        }
    }
}
