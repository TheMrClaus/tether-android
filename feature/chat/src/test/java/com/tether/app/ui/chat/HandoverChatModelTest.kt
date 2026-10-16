package com.tether.app.ui.chat

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-ozn2.3 (#273): the handover attribution of user rows and the provider-notice copy. */
class HandoverChatModelTest {
    private fun s(v: String) = JsStr(v)

    private fun user(fromId: String?, name: String? = "Alpha"): JsObj = JsObj.of(
        "blockId" to s("user:t1"),
        "kind" to s("user_message"),
        "text" to s("hi"),
        "from" to fromId?.let { JsObj.of("sessionId" to s(it), "name" to name?.let(::s)) },
    )

    @Test fun theSuccessorsFirstBlockFromTheSourceReadsHandoverFromWithTheRealCasing() {
        val a = userMessageAttribution(user("src", "Alpha Lead"), "me", "src", firstUserBlock = true)
        assertEquals(UserAttribution(UserAttribution.Kind.Prompt, "Handover from Alpha Lead"), a)
        assertEquals("Handover from the previous session", userMessageAttribution(user("src", "  "), "me", "src", true)?.label)
        assertEquals("Handover from the previous session", userMessageAttribution(user("src", null), "me", "src", true)?.label)
    }

    @Test fun onlyTheFirstUserBlockAndOnlyFromTheSourceGetsThePromptLabel() {
        assertNull(userMessageAttribution(user("src"), "me", "src", firstUserBlock = false))
        assertNull(userMessageAttribution(user("other"), "me", "src", firstUserBlock = true))
        assertNull(userMessageAttribution(user("src"), "me", null, firstUserBlock = true))
    }

    @Test fun aBlockFromTheSessionItselfIsTheInstructionFromTether() {
        val a = userMessageAttribution(user("me", "Tether"), "me", "src", firstUserBlock = false)
        assertEquals(UserAttribution(UserAttribution.Kind.Instruction, "Handover instruction · from Tether"), a)
    }

    @Test fun noFromOrANonUserBlockHasNoLabel() {
        assertNull(userMessageAttribution(user(null), "me", "src", true))
        assertNull(userMessageAttribution(JsObj.of("kind" to s("message")), "me", "src", true))
        assertNull(userMessageAttribution(null, "me", "src", true))
    }

    @Test fun theFirstUserTurnIsTheFirstTurnWithAUserBlock() {
        fun turn(id: String, withUser: Boolean) = JsObj.of("blocksById" to if (withUser) JsObj.of("user:$id" to JsObj.of()) else JsObj.of())
        val state = JsObj.of(
            "turnOrder" to JsArr.of(listOf(s("t0"), s("t1"), s("t2"))),
            "turnsById" to JsObj.of("t0" to turn("t0", false), "t1" to turn("t1", true), "t2" to turn("t2", true)),
        )
        assertEquals("t1", firstUserTurnId(state))
        assertNull(firstUserTurnId(JsObj.of()))
    }

    private fun notices(vararg codes: String?) = providerNotices(
        JsArr.of(codes.mapIndexed { i, c -> JsObj.of("noticeId" to s("n$i"), "level" to s("warning"), "message" to s("raw $i"), "code" to c?.let(::s)) }),
        "Claude",
    )

    @Test fun successNoticesAreHiddenAndExceptionalOnesReadAsOnePlainTetherLine() {
        assertTrue(notices("handover_scheduled", "handover_done", "handover_from").isEmpty())
        val out = notices("handover_cancelled", "handover_not_delivered", "handover_prompt_failed", "handover_row_not_moved", "handover_something_new")
        assertEquals(List(5) { "Tether" }, out.map { it.heading })
        assertEquals(
            listOf(
                "The handover did not complete. Nothing more will be sent automatically.",
                "The handover prompt was not delivered to the new lead.",
                "The handover prompt could not be started.",
                "A queued message could not be moved to the new lead.",
                "The handover did not complete as planned.",
            ),
            out.map { it.message },
        )
        assertTrue("no raw code or id in the line", out.none { "handover_" in it.message || "raw" in it.message })
    }

    private fun noticesV151(vararg codes: String?) = providerNotices(
        JsArr.of(codes.mapIndexed { i, c -> JsObj.of("noticeId" to s("n$i"), "level" to s("info"), "message" to s("raw $i"), "code" to c?.let(::s)) }),
        "Claude",
        v151 = true,
    )

    @Test fun at151TheStoppedAndCancelledNoticesTakeTheWebsWordsKeyedOnTheCodeAlone() {
        val out = noticesV151("handover_operator_stopped", "handover_cancelled", "handover_not_delivered", "handover_scheduled")
        assertEquals(
            listOf(
                "You stopped the handover. Nothing was sent.",
                "The handover could not continue and has ended. Nothing was sent to a new session; waiting messages run here.",
                "The handover prompt was not delivered to the new lead.",
            ),
            out.map { it.message },
        )
        // below 151 the same codes read W33's words, byte for byte (an unknown-to-W33 code is the generic line)
        assertEquals(
            listOf("The handover did not complete as planned.", "The handover did not complete. Nothing more will be sent automatically."),
            notices("handover_operator_stopped", "handover_cancelled").map { it.message },
        )
    }

    @Test fun otherNoticesAreUntouched() {
        val out = notices("model_fallback", null)
        assertEquals(listOf(providerNoticeHeading("Claude", "model_fallback"), "Claude"), out.map { it.heading })
        assertEquals(listOf("raw 0", "raw 1"), out.map { it.message })
    }
}
