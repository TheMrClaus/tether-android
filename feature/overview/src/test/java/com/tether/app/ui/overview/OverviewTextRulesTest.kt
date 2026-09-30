package com.tether.app.ui.overview

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.tether.app.protocol.model.OverviewActivity
import com.tether.app.protocol.model.OverviewAttention
import com.tether.app.protocol.model.OverviewCard
import com.tether.app.protocol.model.OverviewExcerpt
import com.tether.app.protocol.model.OverviewPending
import com.tether.app.protocol.model.OverviewPendingPanel
import com.tether.app.protocol.model.OverviewProviderFacet
import com.tether.app.protocol.model.OverviewWorkspace
import com.tether.app.protocol.model.OverviewWorkspaceFacet
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T15.2 r2: every server/agent string the Overview draws or speaks goes through its rule (T6.4 L5,
 * ta-28i): card titles, provider labels, status details, excerpts, workspace names (card, activity,
 * filter), activity lines, pending summaries and details. No raw bidi control, invisible code point
 * or line break ever reaches the screen or TalkBack; a title of invisibles is spelled out; a
 * workspace name is one-line code, so a spoof shows; the request detail is multi-line code on
 * purpose (it keeps its line break and shows the RLO as a token).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h8000dp-420dpi")
class OverviewTextRulesTest {
    @get:Rule val rule = createComposeRule()

    private val rlo = "\u202E"
    private val lri = "\u2066"
    private val zwsp = "\u200B"
    private val f = OverviewFixtures

    private val hostileTitle = "ev${rlo}il ${lri}x${zwsp}y\nline2 " + "A".repeat(157) + "😀" + "tail"
    private val hostileWsPath = "/srv/te${zwsp}ther"
    private val hostileWsName = "te${zwsp}ther$rlo"
    private val hostileWs = OverviewWorkspace(hostileWsPath, hostileWsName)
    private val detail = "cd /x\n${rlo}rm"

    private fun all(): List<SemanticsNode> =
        rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()

    private fun SemanticsNode.strings(): List<String> =
        config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
            config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty() +
            listOfNotNull(config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text)

    private fun shown(): List<String> = all().flatMap { it.strings() }

    private fun hostileState(): OverviewClientState {
        val base = f.populated
        val pending = OverviewPending(
            "s-h", "r-h", "approval", createdAt = f.NOW - 60_000, title = hostileTitle, provider = "claude",
            summary = "Run ${rlo}rm -rf$lri\n now", detail = detail,
        )
        val card = OverviewCard(
            sessionId = "s-h", title = hostileTitle, provider = "claude", providerLabel = "Cla${rlo}ude\nCode",
            workspace = hostileWs, cwd = "/srv/tether", status = "waiting", statusSince = f.NOW - 60_000,
            attention = OverviewAttention("approval", "Approve ${lri}this$zwsp\nnow"),
            excerpt = OverviewExcerpt("assistant", "said ${rlo}this\nthat"), pending = listOf(pending),
        )
        val invisible = OverviewCard(
            sessionId = "s-inv", title = "$zwsp$zwsp$rlo", provider = "codex", providerLabel = "Codex",
            workspace = hostileWs, status = "running",
        )
        val act = OverviewActivity(
            id = "h1", ts = f.NOW, sessionId = "s-h", title = hostileTitle, provider = "claude", workspace = hostileWsName,
            kind = "request", text = "text ${rlo}x\ny",
        )
        val data = base.data!!
        return base.copy(
            data = data.copy(
                cards = listOf(card, invisible) + data.cards,
                pending = OverviewPendingPanel(listOf(pending), 1, 0),
                facets = data.facets!!.copy(
                    workspaces = data.facets!!.workspaces + OverviewWorkspaceFacet(hostileWsPath, hostileWsName, 2),
                    providers = data.facets!!.providers + OverviewProviderFacet("evil", "claude", label = "Ev${rlo}il\nAI", count = 1),
                ),
            ),
            activity = listOf(act) + base.activity,
        )
    }

    /** A raw hostile code point (a break opportunity, MARK + ZWSP, is inserted by the code rule and is not content). */
    private fun raw(s: String, lineBreaks: Boolean = true): Boolean {
        val t = s.replace("\u2060\u200B", "")
        return t.contains(rlo) || t.contains(lri) || t.contains(zwsp) || (lineBreaks && t.contains('\n'))
    }

    private fun openSelect(name: String) {
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(name))).performClick()
        rule.waitForIdle()
    }

    private fun render(state: OverviewClientState, choice: OverviewChoice = OverviewChoice()) {
        rule.setContent { OverviewUnderTest(TetherSkin.Studio, state, choice = choice) }
        rule.waitForIdle()
    }

    @Test fun hostileServerTextNeverReachesTheScreenOrTalkBackRaw() {
        render(hostileState())
        openSelect("Provider")
        val shown = shown()
        // The request detail is multi-line code on purpose: its line break stays, the RLO is a token.
        val (details, rest) = shown.partition { it.replace("\u2060\u200B", "").startsWith("cd /x") }
        assertTrue("the request detail is drawn: $shown", details.isNotEmpty())
        for (d in details) {
            assertFalse("detail keeps no raw bidi: $d", raw(d, lineBreaks = false))
            assertTrue("detail keeps its line break: $d", d.contains('\n'))
            assertTrue("detail spells the RLO out: $d", d.contains("⟨U+202E⟩"))
        }
        val bad = rest.filter { raw(it) }
        assertTrue("no raw hostile code point: $bad", bad.isEmpty())
        for (s in shown) for (i in s.indices) {
            if (s[i].isHighSurrogate()) assertTrue("lone high surrogate in $s", i + 1 < s.length && s[i + 1].isLowSurrogate())
            if (s[i].isLowSurrogate()) assertTrue("lone low surrogate in $s", i > 0 && s[i - 1].isHighSurrogate())
        }
        // Each hostile surface is drawn at all (so the check above looked at it).
        assertTrue("card title, bounded to the web's 160: $shown", shown.any { it.startsWith("evil xy line2 AAA") && it.endsWith("…") && it.length <= 160 })
        assertTrue("provider label: $shown", shown.any { it == "Claude Code" })
        assertTrue("provider filter label: $shown", shown.any { it == "Evil AI" })
        assertTrue("status detail: $shown", shown.any { it == "Approve thisnow" || it == "Approve this now" })
        assertTrue("excerpt: $shown", shown.any { it == "said this that" })
        assertTrue("pending summary: $shown", shown.any { it == "Run rm -rf now" })
        assertTrue("activity line: $shown", shown.any { it == "text x y" })
        assertTrue("activity row speaks the workspace as code: $shown", shown.any { it.contains(", in workspace te\u2060⟨U+200B⟩ther") })
    }

    @Test fun workspaceNamesAreOneLineCodeLikeTheSidebar() {
        render(hostileState())
        openSelect("Workspace")
        val shown = shown()
        val spelled = "te\u2060⟨U+200B⟩ther\u2060⟨U+202E⟩"
        val spelledPath = "/srv/te\u2060⟨U+200B⟩ther"
        // Two cards' meta and the activity row spell the ZWSP and the RLO out, so "te?ther" never
        // passes for "tether"; so does the workspace filter's option, and its path is code too.
        assertEquals("card and activity workspace: $shown", 3, shown.count { it == spelled })
        assertTrue("the filter option speaks name and path as code: $shown", shown.contains("$spelled, $spelledPath"))
    }

    @Test fun anAllInvisibleTitleIsSpelledOutOnTheCardAndItsButton() {
        render(hostileState())
        val nodes = all()
        val card = nodes.single { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } == OverviewTags.card("s-inv") }
        val inside = nodes.filter { n -> generateSequence(n) { it.parent }.any { it.id == card.id } }.flatMap { it.strings() }
        val spelled = "\\u{200B}\\u{200B}\\u{202E}"
        assertTrue("heading spells the title out: $inside", inside.contains(spelled))
        assertTrue("TalkBack hears the spelled title: $inside", inside.contains("Open session: $spelled"))
    }
}
