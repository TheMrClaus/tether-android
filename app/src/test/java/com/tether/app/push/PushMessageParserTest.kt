package com.tether.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PushMessageParser] against the payloads tether builds today
 * (`lib/fcm-push.mjs` buildFcmMessage / buildFcmSyncMessage, fed by
 * `lib/push-notifications.mjs`). The title/body strings are the server's own
 * generic copy.
 */
class PushMessageParserTest {

    /** The visible shape: `notification {title, body}` + `data {url, kind, tag}`. */
    private fun visible(kind: String, tag: String, title: String, body: String, url: String = "/") =
        PushMessageParser.parse(mapOf("url" to url, "kind" to kind, "tag" to tag), title, body)

    @Test
    fun everyServerKindMapsToItsKind() {
        val cases = listOf(
            Triple("approval", "Tether needs you", PushKind.Approval),
            Triple("question", "Tether needs you", PushKind.Question),
            Triple("turn_end", "Tether turn complete", PushKind.TurnEnd),
            Triple("resume_choice", "Tether: rate limit hit", PushKind.ResumeChoice),
        )
        for ((wire, title, kind) in cases) {
            val message = visible(wire, "tether-$wire-AbC_dEf-123456789012345678", title, "A Claude session needs you.")
            assertTrue("$wire → $message", message is PushMessage.Visible)
            message as PushMessage.Visible
            assertEquals(kind, message.kind)
            assertEquals(title, message.title)
            assertEquals("tether-$wire-AbC_dEf-123456789012345678", message.tag)
        }
    }

    @Test
    fun anUnknownKindWithTextIsStillShownOnTheGeneralKind() {
        val message = visible("something_new", "tether-x-1", "Tether", "Something happened.")
        assertEquals(PushKind.Other, (message as PushMessage.Visible).kind)
        assertEquals(PushKind.Other, PushKind.fromWire(""))
        assertEquals(PushKind.Other, PushKind.fromWire(null))
    }

    @Test
    fun theSyncHintIsRecognisedAndCarriesNothing() {
        assertEquals(PushMessage.SyncHint, PushMessageParser.parse(mapOf("kind" to "sync", "v" to "1"), null, null))
    }

    @Test
    fun aSyncHintWithExtraFieldsIsStillOnlyAHint() {
        // SYNC_DESIGN §6.3: a future server that put content in a hint is ignored,
        // and the kind check wins over any title, so a hint never shows anything.
        val message = PushMessageParser.parse(
            mapOf("kind" to "sync", "v" to "2", "title" to "leak", "body" to "leak", "url" to "/?session=s1", "tag" to "t"),
            "Visible title",
            "Visible body",
        )
        assertEquals(PushMessage.SyncHint, message)
    }

    @Test
    fun aTitleLessDataMessageIsIgnored() {
        val cases = listOf(
            mapOf("kind" to "approval", "tag" to "tether-approval-x", "url" to "/"),
            mapOf("kind" to "approval", "body" to "A session is waiting."),
            mapOf("kind" to "approval", "title" to "", "body" to "A session is waiting."),
            mapOf("kind" to "approval", "title" to "   ", "body" to "A session is waiting."),
            mapOf("kind" to "approval", "title" to "Tether needs you"),
            mapOf("kind" to "approval", "title" to "Tether needs you", "body" to " "),
            emptyMap(),
        )
        for (data in cases) {
            assertEquals("data=$data", PushMessage.Ignored, PushMessageParser.parse(data, null, null))
        }
        assertEquals(PushMessage.Ignored, PushMessageParser.parse(emptyMap(), "", ""))
        assertEquals(PushMessage.Ignored, PushMessageParser.parse(mapOf("kind" to "question"), "Title", null))
    }

    @Test
    fun theNotificationBlockWinsOverDataText() {
        val message = PushMessageParser.parse(
            mapOf("kind" to "approval", "title" to "data title", "body" to "data body"),
            "Tether needs you",
            "A Claude session is waiting for approval.",
        ) as PushMessage.Visible
        assertEquals("Tether needs you", message.title)
        assertEquals("A Claude session is waiting for approval.", message.body)
    }

    @Test
    fun aDataOnlyMessageWithTextIsStillShown() {
        val message = PushMessageParser.parse(
            mapOf("kind" to "turn_end", "title" to "Tether turn complete", "body" to "A session finished its turn."),
            null,
            null,
        )
        assertEquals(PushKind.TurnEnd, (message as PushMessage.Visible).kind)
    }

    @Test
    fun oversizedTextIsCapped() {
        val message = PushMessageParser.parse(mapOf("kind" to "approval"), "t".repeat(5_000), "b".repeat(50_000))
            as PushMessage.Visible
        assertEquals(200, message.title.length)
        assertEquals(1_000, message.body.length)
    }

    // ta-28i: the server's text reaches the system shade through the label rule.
    @Test
    fun bidiControlsAndInvisiblesNeverReachTheShade() {
        val message = PushMessageParser.parse(
            mapOf("kind" to "approval"),
            "Approve \u202Etxt.exe\u202C now",
            "mv \u2066old\u2069 \u2067new\u2069\u200B\u200E\u2060 done\n\nnext",
        ) as PushMessage.Visible
        assertEquals("Approve txt.exe now", message.title)
        assertEquals("mv old new done next", message.body)
        for (text in listOf(message.title, message.body)) {
            for (c in text) {
                assertFalse("U+%04X in \"$text\"".format(c.code), c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u200E' || c == '\u200F' || c == '\u061C' || c == '\u200B' || c == '\u2060')
            }
        }
    }

    @Test
    fun aTitleOfOnlyInvisibleCharactersIsBlankAndTheDataTextIsUsed() {
        val message = PushMessageParser.parse(
            mapOf("kind" to "approval", "title" to "Tether needs you", "body" to "Waiting."),
            "\u202E\u200B\u2066\u2069",
            "\u3164\u2800",
        ) as PushMessage.Visible
        assertEquals("Tether needs you", message.title)
        assertEquals("Waiting.", message.body)
        assertEquals(PushMessage.Ignored, PushMessageParser.parse(mapOf("kind" to "approval"), "\u202E", "body"))
    }

    @Test
    fun theCapNeverSplitsASurrogatePairOrACombiningSequence() {
        // The emoji and the accented letter straddle the 200 / 1,000 bounds.
        for (pad in 196..200) {
            val title = (PushMessageParser.parse(mapOf("kind" to "approval"), "t".repeat(pad) + "\uD83D\uDE00\uD83D\uDE00e\u0301tail", "b") as PushMessage.Visible).title
            assertTrue(title.length <= 200)
            assertTrue(title.endsWith("\u2026"))
            for (i in title.indices) {
                if (Character.isHighSurrogate(title[i])) assertTrue("lone high surrogate: pad $pad", i + 1 < title.length && Character.isLowSurrogate(title[i + 1]))
                if (Character.isLowSurrogate(title[i])) assertTrue("lone low surrogate: pad $pad", i > 0 && Character.isHighSurrogate(title[i - 1]))
            }
            val before = title.dropLast(1)
            assertFalse("a bare 'e' lost its accent: pad $pad", before.endsWith("e") && !before.all { it == 't' })
        }
        for (pad in 995..1_000) {
            val body = (PushMessageParser.parse(mapOf("kind" to "approval"), "t", "b".repeat(pad) + "e\u0301".repeat(10)) as PushMessage.Visible).body
            assertTrue(body.length <= 1_000)
            assertFalse("an accent was cut from its letter: pad $pad", body.dropLast(1).endsWith("e"))
        }
    }

    @Test
    fun realArabicAndHebrewStayWholeAndInOrder() {
        val hebrew = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"
        val arabic = "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645"
        val message = PushMessageParser.parse(mapOf("kind" to "turn_end"), hebrew, arabic) as PushMessage.Visible
        assertEquals(hebrew, message.title)
        assertEquals(arabic, message.body)
    }

    @Test
    fun anUnexpectedTagIsDropped() {
        for (bad in listOf("", "has space", "slash/tag", "a".repeat(129), "new\nline")) {
            val message = visible("approval", bad, "Tether needs you", "Waiting.") as PushMessage.Visible
            assertNull("tag '$bad'", message.tag)
        }
    }

    @Test
    fun aSessionInTheUrlIsNotCarriedIntoTheMessage() {
        // H1: the url is not read on the notification path at all.
        val withSession = visible("approval", "tether-approval-x", "Tether needs you", "Waiting.", url = "/?session=sess-1")
        val without = visible("approval", "tether-approval-x", "Tether needs you", "Waiting.", url = "/")
        assertEquals(without, withSession)
    }

    // sessionIdFromUrl / SessionIds are unwired (kept for T4.4); their rules still hold.
    @Test
    fun sessionIdIsReadOnlyFromTheWebDeepLinkForm() {
        assertEquals("sess-1", PushMessageParser.sessionIdFromUrl("/?session=sess-1"))
        assertEquals("claude:1b2c", PushMessageParser.sessionIdFromUrl("/?session=claude%3A1b2c"))
        assertEquals("abc", PushMessageParser.sessionIdFromUrl("/?x=1&session=abc#frag"))
        assertEquals(
            "0b8f7a8e-6c3d-4e2a-9f1b-2d3c4e5f6a7b",
            PushMessageParser.sessionIdFromUrl("/?session=0b8f7a8e-6c3d-4e2a-9f1b-2d3c4e5f6a7b"),
        )
    }

    @Test
    fun anythingButASameOriginRootLinkNamesNoSession() {
        val rejected = listOf(
            null,
            "",
            "/",
            "/?other=1",
            "/?session=",
            "//evil.example/?session=s1",
            "https://evil.example/?session=s1",
            "intent://x#Intent;end",
            "/settings?session=s1",
            "/?session=..%2F..%2Fetc",
            "/?session=a%20b",
            "/?session=%0Aabc",
            "/?session=-leading-dash",
            "/?session=%E2%80%AEabc",
            "/?session=%ZZ",
            "/?session=" + "a".repeat(129),
            "/?session=s1" + "&x".repeat(300),
        )
        for (url in rejected) assertNull("url=$url", PushMessageParser.sessionIdFromUrl(url))
    }

    @Test
    fun sessionIdShape() {
        assertTrue(SessionIds.isValid("s"))
        assertTrue(SessionIds.isValid("a".repeat(128)))
        assertFalse(SessionIds.isValid("a".repeat(129)))
        assertFalse(SessionIds.isValid(null))
        assertFalse(SessionIds.isValid(""))
        assertFalse(SessionIds.isValid("a/b"))
        assertFalse(SessionIds.isValid(".hidden"))
    }
}
