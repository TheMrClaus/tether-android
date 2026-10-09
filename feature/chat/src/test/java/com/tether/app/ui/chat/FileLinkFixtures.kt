package com.tether.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.tether.app.ui.theme.TetherSkin

/**
 * ta-9jnm fixtures: one agent message that holds every mention shape (the ones that link and the ones that must not), the
 * session folder it is read in, and a transcript that shows it with a recording opener.
 */
object FileLinkFixtures {
    const val CWD = "/w/p"

    /** What a reader taps, in reading order: the resolved absolute path each link opens. */
    val OPENED = listOf(
        "/home/u/a.png", "/tmp/x.log", "/w/p/src/app/Main.kt", "/w/x.md", "/home/u/n.md", "/home/u/n x.md",
        "/a/b.kt", "/a/b.kt", "/a/b.kt", "/a/b.png", "/a/Makefile",
    )

    /** The text each of [OPENED] is drawn as. */
    val DRAWN = listOf(
        "/home/u/a.png", "/tmp/x.log", "src/app/Main.kt", "../x.md", "n", "spaced",
        "/a/b.kt:12", "/a/b.kt:12:3", "/a/b.kt#L10-L20", "/a/b.png", "/a/Makefile",
    )

    const val MESSAGE = "Opened /home/u/a.png and `/tmp/x.log`, then `src/app/Main.kt` and `../x.md`; notes in [n](/home/u/n.md) " +
        "and [spaced](file:///home/u/n%20x.md).\n\n" +
        "At /a/b.kt:12, /a/b.kt:12:3 and /a/b.kt#L10-L20 wrapped (/a/b.png). Then /a/Makefile.\n\n" +
        "Not links: https://example.test/a.png, //cdn/a.js, and/or, 10/09, /api/files, /api/files?path=/a.png, src/a.kt, /usr/bin, ~/a.png.\n\n" +
        "```\n/a/fenced.kt\n```"

    fun folded(reply: String = MESSAGE): ChatFixtures.Folded =
        ChatFixtures.fold(*ChatFixtures.turn("t1", "Where did you put it?", reply, ChatFixtures.T_IDLE))

    /** The opener a board hands the transcript; [opened] records every path a tap would show. */
    fun opener(opened: MutableList<String>, cwd: String = CWD) = WorkspaceFileLinks(cwd) { opened += it }

    /** Every drawn text on screen that carries a link, in tree order. */
    fun linkedTexts(rule: ComposeContentTestRule): List<AnnotatedString> =
        rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("has a link") { it.hasLink() }, useUnmergedTree = false)
            .fetchSemanticsNodes().flatMap { node ->
                node.config[SemanticsProperties.Text].filter { it.getLinkAnnotations(0, it.length).isNotEmpty() }
            }

    private fun SemanticsNode.hasLink(): Boolean =
        config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { s -> s.getLinkAnnotations(0, s.length).isNotEmpty() } == true

    /** Every link on screen as (drawn text, annotation), in reading order. */
    fun links(rule: ComposeContentTestRule): List<Pair<String, LinkAnnotation.Clickable>> =
        linkedTexts(rule).flatMap { text ->
            text.getLinkAnnotations(0, text.length).sortedBy { it.start }.map { text.substring(it.start, it.end) to (it.item as LinkAnnotation.Clickable) }
        }
}

/** The transcript of [fixture] with [opener] provided (null: none), the way the shell hosts it. */
@Composable
internal fun FileLinkBoard(fixture: ChatFixtures.Folded, opener: WorkspaceFileLinks?, showFind: Boolean = false) {
    CompositionLocalProvider(LocalWorkspaceFileOpener provides opener) {
        ChatTranscript(
            projection = fixture.projection,
            tree = fixture.tree,
            showThinking = false,
            onFetchTurns = { _, _ -> },
            zone = ChatFixtures.zone,
            showTimeline = false,
        )
    }
}

fun ComposeContentTestRule.showFileLinks(
    opener: WorkspaceFileLinks?,
    reply: String = FileLinkFixtures.MESSAGE,
    skin: TetherSkin = TetherSkin.StudioDark,
    wellHeight: Dp = WellHeightPhone,
    wellWidth: Dp? = null,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
) {
    val fixture = FileLinkFixtures.folded(reply)
    setContent {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            ChatHost(skin, wellHeight, wellWidth) { FileLinkBoard(fixture, opener) }
        }
    }
    waitForIdle()
}
