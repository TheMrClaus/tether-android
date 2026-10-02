package com.tether.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.ui.components.TetherExpandableBlock
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.regex.Pattern

/**
 * ta-coik.8: a tapped chat link opens at once, like the web's `<a target="_blank">`
 * (markdown.tsx:253): no confirm sheet, whatever its label, wherever it sits. Only what the web
 * renderer also refuses stays refused: its `http://` / `https://` / `mailto:` scheme allowlist
 * (ASCII case only). Every control character in this file is written as an escape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ExternalLinkBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()
    private val recorder = LinkOpener { _: Context, href: String, _: Color -> opened += href }

    private fun host(opener: LinkOpener = recorder, content: @Composable () -> Unit) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                CompositionLocalProvider(LocalLinkOpener provides opener, LocalReducedMotion provides true) { content() }
            }
        }
        rule.waitForIdle()
    }

    @Composable
    private fun Body(markdown: String) {
        MarkdownBody(parseMarkdown(markdown), LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink)
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

    /** No dialog of any kind (the removed sheet was a TetherDialog, a platform dialog window) and no confirm text. */
    private fun assertNoSheet(what: String) {
        assertTrue("$what: a dialog came up", ShadowDialog.getShownDialogs().isEmpty())
        val confirm = hasText("Open this link?") or hasText("Write this email?")
        assertEquals("$what: a confirm text is on screen", 0, rule.onAllNodes(confirm, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    // ---- a tap opens at once ------------------------------------------------------------------

    @Test fun aTapOpensAtOnceWithNoSheet() {
        // Every case the removed sheet asked for: a label that is not its href, emphasis, an
        // international host, an `@`, a long host, a port, an escaped bidi control, a table cell,
        // a mailto with a query; each tapped in the very frame it appeared (no settle delay).
        val cases = listOf(
            "Read [the docs](https://Example.test/docs?q=1) first." to "https://example.test/docs?q=1",
            "[https://x.test/a_b_c](https://x.test/a_b_c)" to "https://x.test/a_b_c",
            "[login](https://ex\u0430mple.com/login)" to "https://xn--exmple-4nf.com/login",
            "[https://evil.example?@bank.example](https://evil.example?@bank.example)" to "https://evil.example?@bank.example",
            "[x](https://bank.example.com.evil.example/)" to "https://bank.example.com.evil.example/",
            "[x](https://${"a".repeat(41)}.com/)" to "https://${"a".repeat(41)}.com/",
            "[staging](https://example.test:8443/a)" to "https://example.test:8443/a",
            "[invoice](https://example.test/%E2%80%AEfdp.exe)" to "https://example.test/%E2%80%AEfdp.exe",
            "| [https://bank.example.evil.co](https://bank.example.evil.co) | b |\n|---|---|\n| c | d |" to "https://bank.example.evil.co",
            "| a | b |\n|---|---|\n| [cell](https://example.test/cell) | d |" to "https://example.test/cell",
            "[write to ops](mailto:ops@Example.test?subject=Deploy&body=see%20log)" to "mailto:ops@example.test?subject=Deploy&body=see%20log",
            "[write](mailto:ops@example.test)" to "mailto:ops@example.test",
        )
        var md by mutableStateOf("Thinking about it")
        host { Body(md) }
        for ((markdown, expected) in cases) {
            opened.clear()
            md = markdown
            rule.waitForIdle()
            tapLink()
            assertEquals(markdown, listOf(expected), opened)
            assertNoSheet(markdown)
        }
    }

    @Test fun aLinkInAClampedBlockOpensAtOnce() {
        val filler = (1..40).joinToString("\n\n") { "Line $it of a long thought." }
        host {
            TetherExpandableBlock(clamp = 120.dp) { Body("[the docs](https://example.test/docs)\n\n$filler") }
        }
        tapLink()
        assertEquals(listOf("https://example.test/docs"), opened)
        assertNoSheet("clamped")
    }

    @Test fun aLinkStreamedIntoAThinkingCardOpensAtOnce() {
        var txt by mutableStateOf("Considering")
        host { ThinkingCard(TurnBlock(blockId = "k", kind = "thinking", text = txt)) }
        rule.onNode(hasContentDescription("Thinking")).performClick()
        rule.waitForIdle()
        txt = "Considering\n\n[see](https://example.com)"
        rule.waitForIdle()
        tapLink()
        assertEquals(listOf("https://example.com"), opened)
        assertNoSheet("thinking")
    }

    // ---- the scheme set is the web renderer's -------------------------------------------------

    /** markdown.tsx `SAFE_HREF = /^(https?:\/\/|mailto:)/i`: JS `/i` without `u` folds ASCII only, as Java's CASE_INSENSITIVE alone does. */
    private val webSafeHref = Pattern.compile("^(https?://|mailto:)", Pattern.CASE_INSENSITIVE)

    private val schemeProbes = listOf(
        "https://example.test/", "http://example.test/a?b=c#d", "HTTPS://Example.test/", "HtTp://example.test/",
        "mailto:ops@example.test", "MAILTO:ops@example.test", "MailTo:ops@example.test?subject=hi",
        "javascript:alert(1)", "JavaScript:alert(1)", "javascript://example.test/%0Aalert(1)", "vbscript:msgbox(1)",
        "data:text/html,x", "intent://x#Intent;scheme=https;end", "file:///etc/passwd", "content://media/x",
        "ftp://example.test/", "tel:+15550100", "sms:+15550100", "geo:0,0", "market://details?id=x",
        "about:blank", "chrome://settings", "tether://session/s1", "ws://example.test/", "blob:https://example.test/x",
        "//example.test/", "/relative/path", "relative", "example.test", "#anchor", "?q=1",
        "http:/example.test", "https:example.test", "http//example.test", "mailto", "mailto//a@b.test",
        "http\u017F://example.test/", "https\u017F://example.test/", "ma\u0131lto:a@example.test", "\u0130ntent://x",
    )

    @Test fun theSchemeSetMatchesTheWebRenderer() {
        var webLinks = 0
        for (href in schemeProbes) {
            val web = webSafeHref.matcher(href).find()
            val app = parseInline("[label]($href)").any { it is MdInline.Link }
            assertEquals("$href: the web renders it as a link = $web", web, app)
            opened.clear()
            assertEquals(href, web, openChatLink(rule.activity, recorder, href, Color.Black))
            assertEquals(href, web, opened.isNotEmpty())
            if (web) webLinks++
        }
        assertEquals("the probes hold both kinds", 7, webLinks)
        // Drawn: exactly the web's links are tappable.
        host { Body(schemeProbes.joinToString("\n\n") { "[label]($it)" }) }
        assertEquals(7, linkCount())
    }

    // ---- the intent ---------------------------------------------------------------------------

    @Test fun theExternalIntentIsABrowsableView() {
        for (href in listOf("https://example.test/docs", "http://example.test/", "mailto:ops@example.test?subject=hi")) {
            val intent = checkNotNull(CustomTabLinkOpener.intentFor(href, Color.Black))
            assertTrue(href, intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertEquals(href, Intent.ACTION_VIEW, intent.action)
            assertEquals(href, href, intent.dataString)
            assertNull(href, intent.component)
        }
    }

    @Test fun aTapStartsTheViewIntentAtOnceInAsciiForm() {
        // Hebrew, CJK and look-alikes outside the host open percent-encoded (what a browser sends).
        val href = "https://example.test/\u05E9\u05DC\u05D5\u05DD/\u6587\u00B7\u30FB\u0660?q=\u2027#\u30CE"
        host(opener = CustomTabLinkOpener) { Body("[docs]($href)") }
        tapLink()
        val started = checkNotNull(shadowOf(rule.activity).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.test/%D7%A9%D7%9C%D7%95%D7%9D/%E6%96%87%C2%B7%E3%83%BB%D9%A0?q=%E2%80%A7#%E3%83%8E", started.dataString)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(started.component)
        assertNoSheet("intent")
    }

    @Test fun aMailtoTapKeepsItsQueryLikeTheWeb() {
        host(opener = CustomTabLinkOpener) { Body("[write to ops](mailto:ops@example.test?subject=Deploy&body=see%20log)") }
        tapLink()
        val started = checkNotNull(shadowOf(rule.activity).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("mailto:ops@example.test?subject=Deploy&body=see%20log", started.dataString)
        val mail = android.net.MailTo.parse(started.dataString!!)
        assertEquals("ops@example.test", mail.to)
        assertEquals("Deploy", mail.subject)
        assertEquals("see log", mail.body)
        assertFalse("mailto goes to the mail app, not a tab", started.hasExtra(CustomTabLinkOpener.EXTRA_SESSION))
    }

    // ---- refused hrefs are inert ---------------------------------------------------------------

    private val hostileHrefs = listOf(
        "https://exa\u202Emple.com/", // RLO in the host
        "https://example.com/\u202Egpj.exe", // RLO in the path
        "https://goo\u200Dgle.com/", // ZWJ
        "https://example.com/\u0007", // C0 (BEL)
        "https://good.com@evil.com/", // user-info
        "javascript:alert(1)",
        "data:text/html,x",
        "intent://x#Intent;end",
    )

    @Test fun refusedHrefsNeverBecomeLinksAndNeverOpen() {
        for (href in hostileHrefs) {
            assertEquals(href, emptyList<MdInline.Link>(), parseInline("[label]($href)").filterIsInstance<MdInline.Link>())
            assertFalse(href, openChatLink(rule.activity, recorder, href, Color.Black))
            assertNull(href, CustomTabLinkOpener.intentFor(href, Color.Black))
        }
        host { Body(hostileHrefs.joinToString("\n\n") { "[label]($it)" }) }
        assertEquals("no link annotation for any refused href", 0, linkCount())
        assertEquals(emptyList<String>(), opened)
    }
}
