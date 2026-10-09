package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-9jnm F3: through the transcript, a tap on a mention hands the host the resolved absolute path (never the token as
 * written, never a line suffix); with no opener provided nothing is a link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FileLinkTapTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()

    private fun tapAll() {
        for ((_, link) in FileLinkFixtures.links(rule)) {
            rule.runOnUiThread { link.linkInteractionListener!!.onClick(link) }
        }
        rule.waitForIdle()
    }

    @Test fun everyMentionOpensItsResolvedPathAndWithoutAnOpenerNoneIsALink() {
        rule.showFileLinks(FileLinkFixtures.opener(opened))
        // Presence first: the links are there, drawn as the text the agent wrote.
        val links = FileLinkFixtures.links(rule)
        assertEquals(FileLinkFixtures.DRAWN, links.map { it.first })
        tapAll()
        assertEquals(FileLinkFixtures.OPENED, opened)
    }

    @Test fun theLinkIsTaggedWithTheResolvedPath() {
        rule.showFileLinks(FileLinkFixtures.opener(opened))
        val tags = FileLinkFixtures.links(rule).map { (it.second.tag) }
        assertEquals(FileLinkFixtures.OPENED.map { "file:$it" }, tags)
    }
}

/** ta-9jnm F3 (second host): the same message, the opener going away while it is on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FileLinkAbsentOpenerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun noOpenerNoLinkAnnotationAnywhere() {
        var opener by androidx.compose.runtime.mutableStateOf<WorkspaceFileLinks?>(FileLinkFixtures.opener(mutableListOf()))
        val fixture = FileLinkFixtures.folded()
        rule.setContent { ChatHost(com.tether.app.ui.theme.TetherSkin.StudioDark) { FileLinkBoard(fixture, opener) } }
        rule.waitForIdle()
        // Presence first: with an opener the message holds its links.
        assertEquals(FileLinkFixtures.DRAWN, FileLinkFixtures.links(rule).map { it.first })
        // Then none: the message is still there, and not one run of it is a link (a markdown href is its label again).
        opener = null
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasText("Opened /home/u/a.png", substring = true)).assertExists()
        assertEquals(emptyList<String>(), FileLinkFixtures.links(rule).map { it.first })
    }
}
