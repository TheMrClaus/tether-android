package com.tether.app.ui.chat

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.lifecycle.Lifecycle
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * ta-fz3: a tapped chat link. An external link whose label is not exactly its printable-ASCII href
 * only opens from the confirm sheet, by an explicit tap on its armed Open key; a refused href is
 * inert text; the sheet never outlives a pause, a stop, a server switch or Lock. Every control
 * character in this file is written as an escape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ExternalLinkBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()
    private var inApp: (String) -> Boolean = { false }
    private val recorder = object : LinkOpener {
        override fun open(context: Context, href: String, toolbarColor: Color) {
            opened += href
        }

        override fun opensInApp(href: String): Boolean = inApp(href)
    }

    /** The links' settle clock (r2): a body opens a link directly only [CONSENT_ARM_DELAY_MS] after it appeared or moved. */
    private var now = 0L

    private fun settleLinks() {
        now += CONSENT_ARM_DELAY_MS + 100
    }

    private fun show(markdown: String, opener: LinkOpener = recorder, settled: Boolean = true) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                CompositionLocalProvider(LocalLinkOpener provides opener, LocalReducedMotion provides true, LocalLinkClock provides { now }) {
                    MarkdownBody(parseMarkdown(markdown), LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink)
                }
            }
        }
        rule.waitForIdle()
        if (settled) settleLinks()
    }

    private val hasLink = SemanticsMatcher("has link") { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { s: AnnotatedString -> s.getLinkAnnotations(0, s.length).isNotEmpty() } == true
    }

    private fun linkCount(): Int = rule.onAllNodes(hasLink).fetchSemanticsNodes().sumOf { node ->
        node.config[SemanticsProperties.Text].sumOf { it.getLinkAnnotations(0, it.length).size }
    }

    /** Tap the (single) link through its annotation, TalkBack's path too. */
    private fun tapLink() {
        val node = rule.onNode(hasLink).fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].first { it.getLinkAnnotations(0, it.length).isNotEmpty() }
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Clickable
        rule.runOnUiThread { link.linkInteractionListener!!.onClick(link) }
        rule.waitForIdle()
    }

    // The dialog surface is clickable, so its content merges into it: read the unmerged tree.
    private fun sheetShown(): Boolean = rule.onAllNodes(androidx.compose.ui.test.hasTestTag(EXTERNAL_LINK_SHEET_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun field(tag: String): String = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    private fun arm() {
        rule.mainClock.advanceTimeBy(CONSENT_ARM_DELAY_MS + 100)
        rule.waitForIdle()
    }

    // ---- label vs href -----------------------------------------------------------------------

    @Test fun aLinkWhoseLabelIsNotItsHrefAsksFirstAndOpensOnlyFromTheArmedKey() {
        show("Read [the docs](https://Example.test/docs?q=1) first.")
        tapLink()
        assertEquals("nothing opens on the tap itself", emptyList<String>(), opened)
        assertTrue(sheetShown())
        rule.onNodeWithText("Open this link?").assertIsDisplayed()
        assertEquals("Host: example.test", field(EXTERNAL_LINK_HOST_TAG))
        assertEquals("Link: https://example.test/docs?q=1", field(EXTERNAL_LINK_TARGET_TAG))
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag(EXTERNAL_LINK_PORT_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
        // The label is never shown in the sheet: only where the link goes.
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("the docs", substring = true) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(EXTERNAL_LINK_SHEET_TAG)), useUnmergedTree = true).fetchSemanticsNodes().size)
        // Armed: not in its first 500 ms.
        val open = rule.onNodeWithTag(EXTERNAL_LINK_OPEN_TAG)
        open.assertIsNotEnabled()
        open.performClick()
        assertEquals(emptyList<String>(), opened)
        arm()
        open.assertIsEnabled().performClick()
        rule.waitForIdle()
        // What opens is what was shown (the host in ASCII lowercase), once, and the sheet is gone.
        assertEquals(listOf("https://example.test/docs?q=1"), opened)
        assertTrue(!sheetShown())
    }

    // ---- r2: the external intent ----------------------------------------------------------------

    @Test fun theExternalIntentIsBrowsableOnly() {
        for (href in listOf("https://example.test/docs", "http://example.test/", "mailto:ops@example.test")) {
            val intent = checkNotNull(CustomTabLinkOpener.intentFor(href, Color.Black))
            assertTrue(href, intent.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
            assertEquals(href, android.content.Intent.ACTION_VIEW, intent.action)
            assertNull(href, intent.component)
        }
    }

    @Test fun theLinkRowIsExactlyTheAsciiIntentData() {
        // r2: Hebrew, CJK and look-alikes outside the host are shown, and open, percent-encoded.
        val href = "https://example.test/\u05E9\u05DC\u05D5\u05DD/\u6587\u00B7\u30FB\u0660?q=\u2027#\u30CE"
        show("[docs]($href)", opener = CustomTabLinkOpener)
        tapLink()
        val row = field(EXTERNAL_LINK_TARGET_TAG).removePrefix("Link: ")
        assertTrue(row, row.all { it in '!'..'~' })
        assertEquals("https://example.test/%D7%A9%D7%9C%D7%95%D7%9D/%E6%96%87%C2%B7%E3%83%BB%D9%A0?q=%E2%80%A7#%E3%83%8E", row)
        arm()
        rule.onNodeWithTag(EXTERNAL_LINK_OPEN_TAG).performClick()
        rule.waitForIdle()
        val started = org.robolectric.Shadows.shadowOf(rule.activity).nextStartedActivity
        assertEquals(row, started.dataString)
        assertTrue(started.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
    }

    // ---- r2: nothing opens directly where the screen may mislead -------------------------------

    @Test fun aLinkThatJustAppearedAsksFirst() {
        show("See [https://example.test/docs](https://example.test/docs).", settled = false)
        tapLink()
        assertEquals("appeared within the arm delay: asks", emptyList<String>(), opened)
        assertTrue(sheetShown())
    }

    @Test fun aLinkThatMovedUnderTheFingerAsksFirst() {
        var above by mutableStateOf(0)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                CompositionLocalProvider(LocalLinkOpener provides recorder, LocalReducedMotion provides true, LocalLinkClock provides { now }) {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.foundation.layout.Spacer(Modifier.height(above.dp))
                        MarkdownBody(parseMarkdown("[https://example.test/docs](https://example.test/docs)"), LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink)
                    }
                }
            }
        }
        rule.waitForIdle()
        settleLinks()
        above = 120 // new output pushes the link down the screen
        rule.waitForIdle()
        tapLink()
        assertEquals(emptyList<String>(), opened)
        assertTrue(sheetShown())
        rule.onNodeWithTag(EXTERNAL_LINK_CANCEL_TAG).performClick()
        rule.waitForIdle()
        // Still for the arm delay: it opens directly again.
        settleLinks()
        tapLink()
        assertEquals(listOf("https://example.test/docs"), opened)
    }

    private fun host(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                CompositionLocalProvider(LocalLinkOpener provides recorder, LocalReducedMotion provides true, LocalLinkClock provides { now }) { content() }
            }
        }
        rule.waitForIdle()
    }

    // r3 (ported from the verifier's probes): content that changes inside a settled body.

    @Test fun linkStreamedIntoASettledBodyAsks() {
        var md by mutableStateOf("Thinking about it")
        host { MarkdownBody(parseMarkdown(md), LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink) }
        settleLinks()
        md = "Thinking about it\n\n[https://example.com](https://example.com)"
        rule.waitForIdle()
        tapLink()
        assertEquals("a link that just appeared under the finger asks", emptyList<String>(), opened)
        assertTrue(sheetShown())
        rule.onNodeWithTag(EXTERNAL_LINK_CANCEL_TAG).performClick()
        rule.waitForIdle()
        settleLinks()
        tapLink()
        assertEquals("settled again: direct", listOf("https://example.com"), opened)
    }

    @Test fun linkPushedDownInsideASettledBodyAsks() {
        var md by mutableStateOf("[https://example.com](https://example.com)")
        host { MarkdownBody(parseMarkdown(md), LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink) }
        settleLinks()
        md = (1..8).joinToString("\n\n") { "New line $it" } + "\n\n[https://example.com](https://example.com)"
        rule.waitForIdle()
        tapLink()
        assertEquals("a link that moved inside its body asks", emptyList<String>(), opened)
        assertTrue(sheetShown())
    }

    @Test fun thinkingStreamAddsLinkToOpenCard() {
        var txt by mutableStateOf("Considering")
        host { ThinkingCard(com.tether.app.protocol.model.TurnBlock(blockId = "k", kind = "thinking", text = txt)) }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Thinking")).performClick()
        rule.waitForIdle()
        settleLinks()
        txt = "Considering\n\n[https://example.com](https://example.com)"
        rule.waitForIdle()
        tapLink()
        assertEquals("a thinking delta that adds a link: asks", emptyList<String>(), opened)
        assertTrue(sheetShown())
    }

    @Test fun aSameSizedContentChangeAsksToo() {
        // The blocks change, the body's size and position do not: the timer still restarts.
        var md by mutableStateOf("[https://example.com](https://example.com) one")
        host { MarkdownBody(parseMarkdown(md), LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink) }
        settleLinks()
        md = "[https://example.org](https://example.org) one"
        rule.waitForIdle()
        tapLink()
        assertEquals(emptyList<String>(), opened)
        assertTrue(sheetShown())
    }

    @Test fun aLinkInATableCellAlwaysAsks() {
        // r3: a cell does not wrap (it scrolls sideways): `https://bank.example.` may be all that shows.
        val link = "[https://bank.example.evil.co](https://bank.example.evil.co)"
        var md by mutableStateOf("| $link | b |\n|---|---|\n| c | d |")
        host { MarkdownBody(parseMarkdown(md), LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink) }
        for (table in listOf(md, "| a | b |\n|---|---|\n| $link | d |")) {
            md = table
            rule.waitForIdle()
            settleLinks()
            tapLink()
            assertEquals(table, emptyList<String>(), opened)
            assertTrue(table, sheetShown())
            rule.onNodeWithTag(EXTERNAL_LINK_CANCEL_TAG).performClick()
            rule.waitForIdle()
        }
        // The same link outside a table opens directly.
        md = "See $link."
        rule.waitForIdle()
        settleLinks()
        tapLink()
        assertEquals(listOf("https://bank.example.evil.co"), opened)
    }

    @Test fun aLinkInsideAClampedBlockAsksFirstUntilTheBlockIsOpen() {
        val filler = (1..40).joinToString("\n\n") { "Line $it of a long thought." }
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                CompositionLocalProvider(LocalLinkOpener provides recorder, LocalReducedMotion provides true, LocalLinkClock provides { now }) {
                    com.tether.app.ui.components.TetherExpandableBlock(clamp = 120.dp) {
                        MarkdownBody(parseMarkdown("[https://example.test/docs](https://example.test/docs)\n\n$filler"), LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink)
                    }
                }
            }
        }
        rule.waitForIdle()
        settleLinks()
        tapLink()
        assertEquals("clamped: asks", emptyList<String>(), opened)
        assertTrue(sheetShown())
        rule.onNodeWithTag(EXTERNAL_LINK_CANCEL_TAG).performClick()
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Show", substring = true) and androidx.compose.ui.test.hasContentDescription("more", substring = true)).performClick()
        rule.waitForIdle()
        settleLinks()
        tapLink()
        assertEquals("open: nothing is cut off", listOf("https://example.test/docs"), opened)
    }

    @Test fun anAtOrALongHostAsksEvenWhenTheLabelMatches() {
        for (href in listOf("https://evil.example?@bank.example", "https://bank.example.com.evil.example/", "https://" + "a".repeat(41) + ".com/")) {
            opened.clear()
            val gate = ExternalLinkGate()
            assertEquals(href, LinkDecision.Confirm, gate.request(rule.activity, recorder, href, href, Color.Black))
            assertEquals(emptyList<String>(), opened)
        }
    }

    @Test fun aLabelThatIsExactlyItsAsciiHrefOpensDirectly() {
        show("See [https://example.test/docs](https://example.test/docs).")
        tapLink()
        assertEquals(listOf("https://example.test/docs"), opened)
        assertTrue(!sheetShown())
    }

    @Test fun anEmphasisedLabelIsComparedAsDrawn() {
        // `_b_` draws "b" in italics: the visible label is "https://x.test/ab_c", not the href.
        show("[https://x.test/a_b_c](https://x.test/a_b_c)")
        tapLink()
        assertEquals(emptyList<String>(), opened)
        assertTrue(sheetShown())
    }

    @Test fun anInternationalHostAsksEvenWhenTheLabelMatchesAndShowsPunycode() {
        val href = "https://ex\u0430mple.com/login"
        show("[$href]($href)")
        tapLink()
        assertEquals(emptyList<String>(), opened)
        assertEquals("Host: xn--exmple-4nf.com", field(EXTERNAL_LINK_HOST_TAG))
        assertEquals("Link: https://xn--exmple-4nf.com/login", field(EXTERNAL_LINK_TARGET_TAG))
        rule.onNodeWithText(EXTERNAL_LINK_IDN_NOTE).assertIsDisplayed()
        arm()
        rule.onNodeWithTag(EXTERNAL_LINK_OPEN_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf("https://xn--exmple-4nf.com/login"), opened)
    }

    @Test fun aPortIsShownOnItsOwnRow() {
        show("[staging](https://example.test:8443/a)")
        tapLink()
        assertEquals("Port: 8443", field(EXTERNAL_LINK_PORT_TAG))
        assertEquals("Link: https://example.test:8443/a", field(EXTERNAL_LINK_TARGET_TAG))
    }

    @Test fun aPercentEscapedBidiControlIsShownEncoded() {
        show("[invoice](https://example.test/%E2%80%AEfdp.exe)")
        tapLink()
        assertEquals("Link: https://example.test/%E2%80%AEfdp.exe", field(EXTERNAL_LINK_TARGET_TAG))
    }

    @Test fun mailtoAsksWithItsRecipients() {
        show("[write to ops](mailto:ops@Example.test?subject=Deploy)")
        tapLink()
        rule.onNodeWithText("Write this email?").assertIsDisplayed()
        assertEquals("To: ops@example.test", field(EXTERNAL_LINK_TO_TAG))
        // r2: the query is dropped: only the address opens, and the sheet shows exactly that.
        assertEquals("Address: mailto:ops@example.test", field(EXTERNAL_LINK_TARGET_TAG))
    }

    // ---- r2: mailto recipients are what the mail app reads --------------------------------------

    /** What a mail app reads from [data] (android.net.MailTo decodes the query, then splits it). */
    private fun mailRecipients(data: String): List<String> {
        val m = android.net.MailTo.parse(data)
        assertEquals("no header but the address line: $data", setOf("to"), m.headers.keys)
        return m.to.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Test fun theMailAppReadsExactlyTheRecipientsTheSheetShows() {
        val payloads = buildList {
            for (name in listOf("to", "cc", "bcc", "TO", "Bcc", "%74o", "b%63c")) {
                for (sep in listOf("&", "%26", "%3F", "%0A", "%0D%0A", "%26amp;")) {
                    for (eq in listOf("=", "%3D", "%3d")) add("subject=Deploy$sep$name${eq}spy@evil.test")
                }
                add("$name=spy@evil.test")
                add("subject=x&$name=spy@evil.test&body=y")
            }
            add("subject=Deploy%26bcc%3Dspy@evil.test")
            add("subject=x%26to%3Devil@x.com")
            add("body=%3Fbcc=spy@evil.test")
            add("")
        }
        // r3: and every character a local part may hold, one at a time.
        val bases = listOf("ops@example.test", "ops@example.test,dev@example.org", "o.p+s@ex\u0430mple.com") +
            "!$&'*+-=^_`{|}~.".map { c -> "a${c}b@example.test" }
        var checked = 0
        for (base in bases) for (q in payloads) {
            val href = if (q.isEmpty()) "mailto:$base" else "mailto:$base?$q"
            val target = com.tether.app.ui.text.SafeHref.target(href) ?: continue
            val intent = checkNotNull(CustomTabLinkOpener.intentFor(target.display, Color.Black))
            // The oracle: what opens, read the way a mail app reads it, names exactly the To row.
            assertEquals(href, target.recipients, mailRecipients(intent.dataString!!))
            assertEquals(href, target.display, intent.dataString)
            checked++
        }
        assertTrue("the sweep reached the allowed links: $checked", checked > 100)
    }

    @Test fun theVerifiersMailtoIsShownAndOpenedAsItsAddressOnly() {
        show("[write to ops](mailto:ops@example.test?subject=Deploy%26bcc%3Dspy@evil.test)", opener = CustomTabLinkOpener)
        tapLink()
        assertEquals("To: ops@example.test", field(EXTERNAL_LINK_TO_TAG))
        arm()
        rule.onNodeWithTag(EXTERNAL_LINK_OPEN_TAG).performClick()
        rule.waitForIdle()
        val started = org.robolectric.Shadows.shadowOf(rule.activity).nextStartedActivity
        assertEquals(listOf("ops@example.test"), mailRecipients(started.dataString!!))
    }

    @Test fun aSessionLinkThatStaysInTheAppNeedsNoSheet() {
        inApp = { it.startsWith("https://tether.test/?session=") }
        show("[the other session](https://tether.test/?session=s1)")
        tapLink()
        assertEquals(listOf("https://tether.test/?session=s1"), opened)
        assertTrue(!sheetShown())
    }

    // ---- refused hrefs are inert ---------------------------------------------------------------

    private val hostileHrefs = listOf(
        "https://exa\u202Emple.com/", // RLO in the host
        "https://example.com/\u202Egpj.exe", // RLO in the path
        "https://example.com/\u2066a\u2069", // LRI .. PDI
        "https://goo\u200Dgle.com/", // ZWJ
        "https://example.com/a\u200Bb", // ZWSP
        "https://pay\u00ADpal.com/", // soft hyphen
        "https://example.com/\u0007", // C0 (BEL)
        "https://example.com/\u0085", // C1 (NEL)
        "https://good.com@evil.com/", // user-info
        "https://google.com\u2215evil.example/", // division slash
        "https://google.com\uFF0Fevil.example/", // fullwidth solidus
        "javascript:alert(1)",
        "data:text/html,x",
        "intent://x#Intent;end",
    )

    @Test fun hostileHrefsNeverBecomeLinks() {
        for (href in hostileHrefs) {
            val md = "[label]($href)"
            val link = parseInline(md).filterIsInstance<MdInline.Link>()
            assertEquals(href, emptyList<MdInline.Link>(), link)
        }
        show(hostileHrefs.joinToString("\n\n") { "[label]($it)" })
        assertEquals("no link annotation for any hostile href", 0, linkCount())
        // And the gate refuses them outright (defence in depth).
        val gate = ExternalLinkGate()
        for (href in hostileHrefs) {
            assertEquals(href, LinkDecision.Refused, gate.request(rule.activity, recorder, href, href, Color.Black))
        }
        assertNull(gate.pending)
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun theRealOpenerRefusesAHostileHrefEvenIfCalledDirectly() {
        for (href in hostileHrefs) assertNull(href, CustomTabLinkOpener.intentFor(href, Color.Black))
    }

    // ---- no retry, no auto-open ----------------------------------------------------------------

    @Test fun aConfirmationOpensOnceAndAStaleOneNever() {
        val gate = ExternalLinkGate()
        assertEquals(LinkDecision.Confirm, gate.request(rule.activity, recorder, "https://example.test/a", "a", Color.Black))
        val first = gate.pending!!
        assertTrue(gate.confirm(first, rule.activity, Color.Black))
        assertTrue("a second tap opens nothing", !gate.confirm(first, rule.activity, Color.Black))
        assertEquals(listOf("https://example.test/a"), opened)
        // A newer request replaces an older one: the older can no longer open.
        gate.request(rule.activity, recorder, "https://example.test/b", "b", Color.Black)
        val older = gate.pending!!
        gate.request(rule.activity, recorder, "https://example.test/c", "c", Color.Black)
        assertTrue(!gate.confirm(older, rule.activity, Color.Black))
        gate.cancel()
        assertTrue(!gate.confirm(older, rule.activity, Color.Black))
        assertEquals(listOf("https://example.test/a"), opened)
    }

    @Test fun cancelClosesAndOpensNothing() {
        show("[docs](https://example.test/docs)")
        tapLink()
        arm()
        rule.onNodeWithTag(EXTERNAL_LINK_CANCEL_TAG).performClick()
        rule.waitForIdle()
        assertTrue(!sheetShown())
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        assertEquals(emptyList<String>(), opened)
    }

    // ---- the sheet never outlives what it was opened for ---------------------------------------

    @Test fun theSheetClosesWhenTheActivityPausesAndWhenItStops() {
        show("[docs](https://example.test/docs)")
        tapLink()
        assertTrue(sheetShown())
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED) // ON_PAUSE (a screen lock pauses first)
        rule.waitForIdle()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertTrue("paused: closed, and it does not come back", !sheetShown())
        tapLink()
        assertTrue(sheetShown())
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // ON_STOP
        rule.waitForIdle()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue("stopped: closed, and it does not come back", !sheetShown())
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun aServerSwitchOrLockDropsThePendingLink() {
        // UiRoot's wiring: one gate per signed-in server, provided with its host; Lock removes both.
        var server by mutableStateOf("https://a.test")
        var signedIn by mutableStateOf(true)
        var current: ExternalLinkGate? = null
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                CompositionLocalProvider(LocalLinkOpener provides recorder, LocalReducedMotion provides true) {
                    if (signedIn) {
                        val gate = remember(server) { ExternalLinkGate() }
                        current = gate
                        CompositionLocalProvider(LocalExternalLinkGate provides gate) {
                            MarkdownBody(parseMarkdown("[docs](https://example.test/docs)"), LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink)
                            ExternalLinkConfirmHost(gate)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        tapLink()
        val before = current!!
        assertTrue(sheetShown())
        server = "https://b.test"
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
        assertTrue("server switch: closed", !sheetShown())
        assertNull("the old server's gate holds nothing", before.pending)
        tapLink()
        assertTrue(sheetShown())
        val second = current!!
        signedIn = false
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
        assertTrue("Lock: closed", !sheetShown())
        assertNull(second.pending)
        signedIn = true
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue("signing in again shows nothing", !sheetShown())
        assertEquals(emptyList<String>(), opened)
    }

    // ---- overlays ------------------------------------------------------------------------------

    /** The compose root of the dialog window on top. */
    private fun dialogRoot(): View {
        val decor = ShadowDialog.getLatestDialog().window!!.decorView
        fun find(v: View): View? {
            if (v.javaClass.simpleName == "AndroidComposeView") return v
            if (v is ViewGroup) for (n in 0 until v.childCount) find(v.getChildAt(n))?.let { return it }
            return null
        }
        return checkNotNull(find(decor))
    }

    private fun tapWithFlags(tag: String, flags: Int) {
        val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val view = dialogRoot()
        rule.runOnUiThread {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = bounds.center.x; y = bounds.center.y; pressure = 1f; size = 1f })
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val e = MotionEvent.obtain(0L, 10L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags)
                view.dispatchTouchEvent(e)
                e.recycle()
            }
        }
        rule.waitForIdle()
    }

    @Test fun aTouchThroughAnOverlayCannotOpen() {
        show("[docs](https://example.test/docs)")
        tapLink()
        arm()
        tapWithFlags(EXTERNAL_LINK_OPEN_TAG, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        tapWithFlags(EXTERNAL_LINK_OPEN_TAG, FLAG_PARTIALLY_OBSCURED)
        assertEquals("an obscured touch opened a link", emptyList<String>(), opened)
        assertTrue(sheetShown())
        // The same touch, unobscured, is a tap: the filter is what refused it.
        tapWithFlags(EXTERNAL_LINK_OPEN_TAG, 0)
        assertEquals(listOf("https://example.test/docs"), opened)
    }
}
