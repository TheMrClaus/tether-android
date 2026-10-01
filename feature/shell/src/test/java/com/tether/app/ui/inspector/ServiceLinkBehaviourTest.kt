package com.tether.app.ui.inspector

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.ui.chat.CustomTabLinkOpener
import com.tether.app.ui.chat.EXTERNAL_LINK_CANCEL_TAG
import com.tether.app.ui.chat.EXTERNAL_LINK_OPEN_TAG
import com.tether.app.ui.chat.EXTERNAL_LINK_TARGET_TAG
import com.tether.app.ui.chat.ExternalLinkConfirmHost
import com.tether.app.ui.chat.ExternalLinkGate
import com.tether.app.ui.chat.LinkOpener
import com.tether.app.ui.chat.LocalExternalLinkGate
import com.tether.app.ui.chat.LocalLinkOpener
import com.tether.app.ui.chat.SERVICE_LINK_BODY
import com.tether.app.ui.chat.SERVICE_LINK_SERVICE_TAG
import com.tether.app.ui.chat.SERVICE_LINK_SHEET_TAG
import com.tether.app.ui.inspector.InspectorBoards.ORIGIN
import com.tether.app.ui.inspector.InspectorBoards.obj
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
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
 * T15.7: a running worktree service's "Open". A tap only raises the confirm sheet (the service's
 * host, the link, "signed in to Tether"); nothing opens until the armed Open key; Cancel opens
 * nothing; what opens is a browsable-only external intent carrying no app credential, never the
 * in-app link router; nothing about it is logged. Every control character in this file is an escape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ServiceLinkBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val link = "/api/worktree/open?session=s1&script=web"
    private val url = "$ORIGIN$link"
    private val opened = mutableListOf<String>()
    private val recorder = LinkOpener { _: Context, href: String, _: Color -> opened += href }

    @Before fun clearLogs() {
        ShadowLog.clear()
    }

    private fun model(host: String = "web--feat.svc.example.test", authUrl: String? = link): InspectorModel {
        val auth = authUrl?.let { JsonPrimitive(it).toString() } ?: "null"
        val replies = InspectorReplies(
            worktreeScripts = obj(
                """{"sessionId":"s1","setupStatus":"ok","setupLog":[],"configWarnings":[],"scripts":[
                   {"name":"web","type":"service","command":"npm run dev","status":"running","port":5173,
                    "proxyHost":${JsonPrimitive(host)},"proxyUrl":"https://web--feat.svc.example.test","proxyPath":null,
                    "proxyAuthUrl":$auth,"proxyUnavailable":null}]}""",
            ),
        )
        return InspectorBoards.model(InspectorBoards.session(worktree = WorktreeInfo(path = "/w", branch = "b", status = "active")), replies = replies)
    }

    private fun show(model: () -> InspectorModel, opener: LinkOpener = recorder, gate: ExternalLinkGate? = null, inApp: LinkOpener? = null) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalExternalLinkGate provides gate, LocalLinkOpener provides (inApp ?: CustomTabLinkOpener)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Inspector(model(), null, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env }, serviceOpener = opener)
                    }
                    if (gate != null) ExternalLinkConfirmHost(gate)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tapOpen() {
        rule.onNodeWithTag(InspectorTags.ServiceOpen).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun sheetShown(): Boolean = rule.onAllNodes(hasTestTag(SERVICE_LINK_SHEET_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun field(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    private fun arm() {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    @Test fun aTapAsksFirstAndShowsTheServiceNotTheLabel() {
        show({ model() })
        tapOpen()
        assertEquals("nothing opens on the tap", emptyList<String>(), opened)
        assertTrue(sheetShown())
        rule.onNodeWithText("Open this service?").assertExists()
        rule.onNodeWithText(SERVICE_LINK_BODY).assertExists()
        assertEquals("Service: web--feat.svc.example.test", field(SERVICE_LINK_SERVICE_TAG))
        assertEquals("Link: $url", field(EXTERNAL_LINK_TARGET_TAG))
    }

    @Test fun cancelOpensNothing() {
        show({ model() })
        tapOpen()
        arm()
        rule.onNodeWithTag(EXTERNAL_LINK_CANCEL_TAG).performClick()
        rule.waitForIdle()
        assertFalse(sheetShown())
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun itNeverOpensOnItsOwnAndOnlyOnceFromTheArmedKey() {
        show({ model() })
        tapOpen()
        // Not armed in its first moments: a tap there does nothing.
        rule.onNodeWithTag(EXTERNAL_LINK_OPEN_TAG).assertIsNotEnabled().performClick()
        // Time alone never opens it.
        rule.mainClock.advanceTimeBy(10_000)
        rule.waitForIdle()
        assertEquals(emptyList<String>(), opened)
        assertTrue(sheetShown())
        rule.onNodeWithTag(EXTERNAL_LINK_OPEN_TAG).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf(url), opened)
        assertFalse(sheetShown())
    }

    @Test fun theIntentIsBrowsableOnlyCarriesNoCredentialAndSkipsTheInAppRouter() {
        val inAppCalls = mutableListOf<String>()
        val inApp = object : LinkOpener {
            override fun open(context: Context, href: String, toolbarColor: Color) {
                inAppCalls += href
            }

            override fun opensInApp(href: String): Boolean = true
        }
        show({ model() }, opener = CustomTabLinkOpener, inApp = inApp)
        tapOpen()
        arm()
        rule.onNodeWithTag(EXTERNAL_LINK_OPEN_TAG).performClick()
        rule.waitForIdle()
        assertEquals("never the in-app link router", emptyList<String>(), inAppCalls)
        val intent: Intent = checkNotNull(Shadows.shadowOf(rule.activity).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertNull("no explicit component", intent.component)
        assertNull("no explicit package", intent.`package`)
        assertEquals("no URI permission grants", 0, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        val extras = intent.extras?.keySet().orEmpty()
        assertEquals(setOf(CustomTabLinkOpener.EXTRA_SESSION, CustomTabLinkOpener.EXTRA_TOOLBAR_COLOR), extras)
        assertFalse("no browser headers (Authorization, Cookie)", android.provider.Browser.EXTRA_HEADERS in extras)
        assertNull(Shadows.shadowOf(rule.activity).nextStartedActivity)
    }

    @Test fun aHostileHostIsDrawnWithVisibleTokensInTheRowAndTheSheet() {
        val host = "web\u202Egnp.evil.example\nforged-row\u200B"
        show({ model(host = host) })
        val raw = listOf('\u202E', '\n', '\u200B')
        fun texts() = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { n -> n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } }
        val row = texts().first { "Own address" in it }
        assertTrue(row, raw.none { it in row })
        tapOpen()
        val spoken = field(SERVICE_LINK_SERVICE_TAG)
        assertEquals("Service: ${SafeText.line(host)}", spoken)
        assertTrue(spoken, raw.none { it in spoken })
        assertTrue(texts().filter { "gnp" in it }.all { t -> raw.none { it in t } })
    }

    @Test fun aRefusedLinkHasNoOpenKeyAndSaysNotConfigured() {
        show({ model(authUrl = "javascript:alert(1)") })
        assertEquals(0, rule.onAllNodes(hasTestTag(InspectorTags.ServiceOpen)).fetchSemanticsNodes().size)
        rule.onNodeWithText("Own address not configured", substring = true).assertExists()
    }

    @Test fun thePendingSheetClosesWhenTheLinkGoesAway() {
        val gate = ExternalLinkGate()
        var current by mutableStateOf(model())
        show({ current }, gate = gate)
        tapOpen()
        assertEquals("web--feat.svc.example.test", gate.pending?.service)
        // The service stopped: the snapshot has no link any more.
        current = model(authUrl = null)
        rule.waitForIdle()
        assertNull(gate.pending)
        assertFalse(sheetShown())
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun nothingAboutTheLinkIsLogged() {
        show({ model() }, opener = CustomTabLinkOpener)
        tapOpen()
        arm()
        rule.onNodeWithTag(EXTERNAL_LINK_OPEN_TAG).performClick()
        rule.waitForIdle()
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg} ${it.throwable ?: ""}" }
        for (needle in listOf("worktree/open", "session=s1", "console.example.test", "web--feat")) {
            assertFalse(needle, needle in logged)
        }
    }
}
