package com.tether.app.ui.inspector

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tether.app.client.ServiceOpenSource
import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.ui.chat.CustomTabLinkOpener
import com.tether.app.ui.chat.LinkOpener
import com.tether.app.ui.chat.LocalLinkOpener
import com.tether.app.ui.inspector.InspectorBoards.ORIGIN
import com.tether.app.ui.inspector.InspectorBoards.obj
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * T15.7 / ta-coik.2: a running worktree service's links (worktree-services-card.tsx:141-154). As on
 * the web, a tap opens at once (no confirm sheet): "Open" asks the console's worktree-open route
 * with the app's sign-in and sends the browser where it redirects (the handoff target), "On this
 * machine" opens the console-origin path form. What opens is a browsable-only external intent
 * carrying no app credential, never the in-app link router; a refusal says why and opens nothing;
 * nothing about it is logged. Every control character in this file is an escape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ServiceLinkBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val link = "/api/worktree/open?session=s1&script=web"
    private val url = "$ORIGIN$link"
    private val handoff = "https://web--feat.svc.example.test/?tether-auth=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde"
    private val path = "/services/~0abc.AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde/s1/web/"
    private val opened = mutableListOf<String>()
    private val recorder = LinkOpener { _: Context, href: String, _: Color -> opened += href }

    /** Records what it was asked; answers [answer] (or waits on [gate] first). */
    private class FakeOpen(var answer: ServiceOpenSource.Outcome, val gate: CompletableDeferred<Unit>? = null) : ServiceOpenSource {
        val asked = mutableListOf<Pair<String, String?>>()

        override suspend fun open(link: String, serviceUrl: String?): ServiceOpenSource.Outcome {
            asked += link to serviceUrl
            gate?.await()
            return answer
        }
    }

    @Before fun clearLogs() {
        ShadowLog.clear()
    }

    private fun model(host: String = "web--feat.svc.example.test", authUrl: String? = link, proxyPath: String? = null): InspectorModel {
        val auth = authUrl?.let { JsonPrimitive(it).toString() } ?: "null"
        val local = proxyPath?.let { JsonPrimitive(it).toString() } ?: "null"
        val replies = InspectorReplies(
            worktreeScripts = obj(
                """{"sessionId":"s1","setupStatus":"ok","setupLog":[],"configWarnings":[],"scripts":[
                   {"name":"web","type":"service","command":"npm run dev","status":"running","port":5173,
                    "proxyHost":${JsonPrimitive(host)},"proxyUrl":"https://web--feat.svc.example.test","proxyPath":$local,
                    "proxyAuthUrl":$auth,"proxyUnavailable":null}]}""",
            ),
        )
        return InspectorBoards.model(InspectorBoards.session(worktree = WorktreeInfo(path = "/w", branch = "b", status = "active")), replies = replies)
    }

    private fun show(model: InspectorModel, source: ServiceOpenSource, opener: LinkOpener = recorder, inApp: LinkOpener? = null) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalLinkOpener provides (inApp ?: CustomTabLinkOpener)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Inspector(model, null, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env }, serviceOpener = opener, serviceOpen = source)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun openAsksTheConsoleWithTheAppSignInAndOpensWhereItRedirectsAtOnce() {
        val source = FakeOpen(ServiceOpenSource.Outcome.Open(handoff))
        show(model(), source)
        tap(InspectorTags.ServiceOpen)
        assertEquals("asked with the snapshot's proxyUrl (ta-t5rl)", listOf(url to "https://web--feat.svc.example.test"), source.asked)
        assertEquals("opened on the tap, no confirm sheet", listOf(handoff), opened)
        assertEquals(0, count(InspectorTags.ServiceOpenRefusal))
    }

    @Test fun aRefusalSaysWhyAndOpensNothing() {
        val source = FakeOpen(ServiceOpenSource.Outcome.Refused("That service has no proxied address."))
        show(model(), source)
        tap(InspectorTags.ServiceOpen)
        assertEquals(emptyList<String>(), opened)
        rule.onNodeWithTag(InspectorTags.ServiceOpenRefusal, useUnmergedTree = true).assertExistsAndSays("That service has no proxied address.")
        // A later success clears it.
        source.answer = ServiceOpenSource.Outcome.Open(handoff)
        tap(InspectorTags.ServiceOpen)
        assertEquals(listOf(handoff), opened)
        assertEquals(0, count(InspectorTags.ServiceOpenRefusal))
    }

    @Test fun aSecondTapWhileTheConsoleIsAskedSendsNothingMore() {
        val gate = CompletableDeferred<Unit>()
        val source = FakeOpen(ServiceOpenSource.Outcome.Open(handoff), gate)
        show(model(), source)
        tap(InspectorTags.ServiceOpen)
        tap(InspectorTags.ServiceOpen)
        assertEquals(1, source.asked.size)
        gate.complete(Unit)
        rule.waitForIdle()
        assertEquals(listOf(handoff), opened)
    }

    @Test fun onThisMachineOpensThePathFormOnThePairedOrigin() {
        val source = FakeOpen(ServiceOpenSource.Outcome.Open(handoff))
        show(model(proxyPath = path), source)
        rule.onNodeWithText("On this machine").assertExists()
        tap(InspectorTags.ServiceLocal)
        assertEquals(listOf("$ORIGIN$path"), opened)
        assertEquals("the path form never asks the worktree-open route", emptyList<Pair<String, String?>>(), source.asked)
        // Both links, like the web, when both were sent.
        assertEquals(1, count(InspectorTags.ServiceOpen))
    }

    @Test fun noPathFormNoOnThisMachine() {
        show(model(), FakeOpen(ServiceOpenSource.Outcome.Open(handoff)))
        assertEquals(0, count(InspectorTags.ServiceLocal))
        assertEquals(1, count(InspectorTags.ServiceOpen))
    }

    @Test fun onThisMachineAloneShowsWithTheNotConfiguredText() {
        show(model(authUrl = null, proxyPath = path), FakeOpen(ServiceOpenSource.Outcome.Open(handoff)))
        assertEquals(0, count(InspectorTags.ServiceOpen))
        assertEquals(1, count(InspectorTags.ServiceLocal))
        rule.onNodeWithText("Own address not configured", substring = true).assertExists()
    }

    @Test fun theIntentIsBrowsableOnlyCarriesNoCredentialAndSkipsTheInAppRouter() {
        val inAppCalls = mutableListOf<String>()
        val inApp = object : LinkOpener {
            override fun open(context: Context, href: String, toolbarColor: Color) {
                inAppCalls += href
            }
        }
        show(model(), FakeOpen(ServiceOpenSource.Outcome.Open(handoff)), opener = CustomTabLinkOpener, inApp = inApp)
        tap(InspectorTags.ServiceOpen)
        assertEquals("never the in-app link router", emptyList<String>(), inAppCalls)
        val intent: Intent = checkNotNull(Shadows.shadowOf(rule.activity).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(handoff, intent.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertNull("no explicit component", intent.component)
        assertNull("no explicit package", intent.`package`)
        assertEquals("no URI permission grants", 0, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        val extras = intent.extras?.keySet().orEmpty()
        assertEquals(setOf(CustomTabLinkOpener.EXTRA_SESSION, CustomTabLinkOpener.EXTRA_TOOLBAR_COLOR), extras)
        assertFalse("no browser headers (Authorization, Cookie)", android.provider.Browser.EXTRA_HEADERS in extras)
        assertNull(Shadows.shadowOf(rule.activity).nextStartedActivity)
    }

    @Test fun aHostileHostIsDrawnWithVisibleTokensInTheRow() {
        val host = "web\u202Egnp.evil.example\nforged-row\u200B"
        show(model(host = host), FakeOpen(ServiceOpenSource.Outcome.Refused("x")))
        val raw = listOf('\u202E', '\n', '\u200B')
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { n -> n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } }
        val row = texts.first { "Own address" in it }
        assertTrue(row, raw.none { it in row })
    }

    @Test fun aRefusedLinkHasNoOpenKeyAndSaysNotConfigured() {
        show(model(authUrl = "javascript:alert(1)"), FakeOpen(ServiceOpenSource.Outcome.Open(handoff)))
        assertEquals(0, count(InspectorTags.ServiceOpen))
        rule.onNodeWithText("Own address not configured", substring = true).assertExists()
    }

    @Test fun nothingAboutTheLinksIsLogged() {
        show(model(proxyPath = path), FakeOpen(ServiceOpenSource.Outcome.Open(handoff)), opener = CustomTabLinkOpener)
        tap(InspectorTags.ServiceOpen)
        tap(InspectorTags.ServiceLocal)
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg} ${it.throwable ?: ""}" }
        for (needle in listOf("worktree/open", "session=s1", "console.example.test", "web--feat", "tether-auth", "/services/")) {
            assertFalse(needle, needle in logged)
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsAndSays(text: String) {
        val node = fetchSemanticsNode()
        assertEquals(text, node.config[SemanticsProperties.Text].joinToString("") { it.text })
    }
}
