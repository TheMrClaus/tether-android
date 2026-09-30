package com.tether.app.ui.chat

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.text.SafeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7.3 r3: the command panel draws output by the shared terminal rule ([SafeText.terminal], ta-blf):
 * SGR colour is dropped (a bounded number of digits and separators only). Everything else is
 * SHOWN, never interpreted or hidden: every ESC / C0 / C1, every bidi control and every invisible
 * code point becomes a visible token, and the parameters and payloads stay as plain text. The
 * operator sees what the agent sees. These are the r2 escape cases rewritten for that rule.
 */
class CommandOutputRuleTest {

    /** A drawn text with the token marks and break opportunities taken out, for readable asserts. */
    private fun shown(raw: String): String = SafeText.terminal(raw).replace("${SafeText.MARK}\u200B", "").replace(SafeText.MARK.toString(), "")

    private fun panel(vararg texts: String): String =
        commandPanelText(JsArr.of(texts.map { JsObj.of("stream" to JsStr("stdout"), "text" to JsStr(it)) }))
            .segments.joinToString("") { it.text }

    @Test
    fun sgrColourIsDroppedAndNothingElse() {
        assertEquals("ok plain", shown("\u001B[32mok\u001B[0m plain"))
        assertEquals("bold", shown("\u001B[1;38;2;255;255;255mbold"))
        // Erase / cursor moves: shown.
        assertEquals("⟨U+001B⟩[2Kok", shown("\u001B[2Kok"))
        assertEquals("⟨U+001B⟩[?25l", shown("\u001B[?25l"))
    }

    @Test
    fun aCsiWithUnusuallyLongParametersShows() {
        val long = "\u001B[" + "1".repeat(SafeText.MAX_SGR_PARAMS + 1) + "m"
        assertEquals("⟨U+001B⟩[" + "1".repeat(SafeText.MAX_SGR_PARAMS + 1) + "m", shown(long))
        // At the bound it is still an SGR.
        assertEquals("x", shown("\u001B[" + "1".repeat(SafeText.MAX_SGR_PARAMS) + "mx"))
    }

    @Test
    fun anEscBeforeEveryLetterNeverHidesTheWord() {
        val raw = "hello".map { "\u001B$it" }.joinToString("")
        val drawn = shown(raw)
        assertEquals("⟨U+001B⟩h⟨U+001B⟩e⟨U+001B⟩l⟨U+001B⟩l⟨U+001B⟩o", drawn)
        assertEquals("hello", drawn.replace("⟨U+001B⟩", ""))
    }

    @Test
    fun chainedTerminatedStringSequencesHideNoPayload() {
        val raw = "\u001B]0;title\u0007\u001B]52;c;c2VjcmV0\u001B\\\u001BPqdcs\u001B\\tail"
        assertEquals("⟨U+001B⟩]0;title⟨U+0007⟩⟨U+001B⟩]52;c;c2VjcmV0⟨U+001B⟩\\⟨U+001B⟩Pqdcs⟨U+001B⟩\\tail", shown(raw))
    }

    @Test
    fun aLongPayloadIsShownWhole() {
        val payload = "A".repeat(5_000)
        val drawn = shown("\u001B]52;c;$payload\u0007visible")
        assertTrue(drawn.contains(payload))
        assertTrue(drawn.endsWith("⟨U+0007⟩visible"))
    }

    @Test
    fun anUnterminatedSequenceHidesNothingAfterIt() {
        assertEquals("⟨U+001B⟩]0;title\nline two", shown("\u001B]0;title\nline two"))
        assertEquals("⟨U+001B⟩]8;;x⟨U+0018⟩after", shown("\u001B]8;;x\u0018after"))
        assertEquals("⟨U+001B⟩[31;1⟨U+0001⟩ still here", shown("\u001B[31;1\u0001 still here"))
    }

    @Test
    fun eightBitC1FormsShowAsTokens() {
        assertEquals("⟨U+009D⟩52;c;x⟨U+009C⟩ visible", shown("\u009D52;c;x\u009C visible"))
        assertEquals("⟨U+009B⟩31mred", shown("\u009B31mred"))
        assertEquals("⟨U+0090⟩qpayload⟨U+001B⟩\\ok", shown("\u0090qpayload\u001B\\ok"))
    }

    @Test
    fun anEscBeforeANonBmpCharacterLeavesNoLoneSurrogate() {
        val drawn = SafeText.terminal("\u001B😀 ok")
        assertEquals("⟨U+001B⟩😀 ok", shown("\u001B😀 ok"))
        for (i in drawn.indices) {
            if (Character.isHighSurrogate(drawn[i])) assertTrue(i + 1 < drawn.length && Character.isLowSurrogate(drawn[i + 1]))
            if (Character.isLowSurrogate(drawn[i])) assertTrue(i > 0 && Character.isHighSurrogate(drawn[i - 1]))
        }
    }

    @Test
    fun bidiAndInvisibleCodePointsAreTokensPerTheCodeRule() {
        assertEquals("Approve ⟨U+202E⟩gnp.exe⟨U+202C⟩", shown("Approve \u202Egnp.exe\u202C"))
        assertEquals("a⟨U+200B⟩b⟨U+2066⟩c⟨U+2800⟩", shown("a\u200Bb\u2066c\u2800"))
        assertEquals("x⟨U+0000⟩y⟨U+007F⟩", shown("x\u0000y\u007F"))
        // A CRLF stays a line break; a lone CR is shown.
        assertEquals("a\r\nb⟨U+000D⟩c", shown("a\r\nb\rc"))
    }

    @Test
    fun thePanelsTailIsNeverCutInsideAToken() {
        // A drawn tail cut at the bound must start on a unit boundary.
        val raw = "\u0001x".repeat(COMMAND_PANEL_MAX_CHARS)
        val text = panel(raw)
        assertTrue(text.length <= COMMAND_PANEL_MAX_CHARS)
        val first = text.first()
        assertTrue("starts with a whole token or a character, not half a token: ${text.take(12)}", first == SafeText.MARK || first == 'x')
        if (first == SafeText.MARK) assertTrue(SafeText.unitAt(text, 0) != null)
        assertFalse(text.contains('\u0001'))
    }

    @Test
    fun aCopyOfThePanelNeverCarriesAHiddenControl() {
        // The command block is a selectable transcript row, so a copy goes through
        // ChatTranscript's SafeCopyClipboard, which puts SafeText.forCopy of the drawn text on the clipboard.
        val drawn = panel("ok \u001B]52;c;x\u0007 \u202Edone")
        val copied = SafeText.forCopy(drawn)
        assertFalse(copied.text.any { it == '\u001B' || it == '\u0007' || it == '\u202E' })
        assertEquals(3, copied.hidden)
        val items = buildChatItems(CommandFixtures.running.projection, CommandFixtures.running.tree, showThinking = false)
        assertTrue(items.filterIsInstance<ChatItem.Block>().single { it.block.kind == COMMAND_OUTPUT_BLOCK }.selectableText)
    }
}
