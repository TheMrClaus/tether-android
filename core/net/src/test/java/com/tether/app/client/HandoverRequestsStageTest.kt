package com.tether.app.client

import com.tether.app.protocol.model.AgentSession
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-9wdf (v151): a server stage counts like handoverPending in the claims, so a Stop from "requested" settles. */
class HandoverRequestsStageTest {
    private fun s(stage: String? = null, pending: Boolean = false) = AgentSession(
        id = "a", provider = "claude", name = "a", cwd = "/w", status = "idle", startedAt = 1, updatedAt = 1,
        handoverStage = stage?.let(::JsonPrimitive), handoverPending = pending,
    )

    @Test fun aStageThenItsDisappearanceWithNoLinkSettlesTheClaim() {
        val claims = HandoverRequests()
        claims.add("a")
        claims.settle(listOf(s("requested")))
        assertEquals(setOf("a"), claims.requested.value)
        claims.settle(listOf(s()))
        assertTrue("a Stop from the requested stage must not leave the flag stuck", claims.requested.value.isEmpty())
    }

    @Test fun aRefusalKeepsAClaimTheServerEchoedAsAStage() {
        val claims = HandoverRequests()
        claims.add("a")
        claims.onRefusal(listOf(s("requested")))
        assertEquals(setOf("a"), claims.requested.value)
        claims.onRefusal(listOf(s()))
        assertTrue(claims.requested.value.isEmpty())
    }

    @Test fun pendingStillSettlesAsInW33() {
        val claims = HandoverRequests()
        claims.add("a")
        claims.settle(listOf(s(pending = true)))
        claims.settle(listOf(s()))
        assertTrue(claims.requested.value.isEmpty())
    }
}
