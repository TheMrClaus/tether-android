package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-9jnm F4: a link is reachable without sight: each one is its own semantics node with a click action under the message
 * (not only coloured text), and that action opens the same path a touch does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FileLinkA11yTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()

    private fun linkNodes(): List<SemanticsNode> =
        rule.onAllNodes(SemanticsMatcher("a link node") { it.config.getOrNull(SemanticsActions.OnClick) != null && it.parent?.config?.getOrNull(SemanticsProperties.Text) != null }, useUnmergedTree = true)
            .fetchSemanticsNodes()

    @Test fun eachLinkIsItsOwnNodeWithAClickThatOpensItsPath() {
        rule.showFileLinks(FileLinkFixtures.opener(opened))
        val nodes = linkNodes()
        // One node per link, no more.
        assertEquals(FileLinkFixtures.OPENED.size, nodes.size)
        // They sit under the message text that holds their links.
        for (node in nodes) assertTrue(node.parent!!.config[SemanticsProperties.Text].any { it.getLinkAnnotations(0, it.length).isNotEmpty() })
        for (node in nodes) {
            rule.runOnUiThread { node.config[SemanticsActions.OnClick].action!!.invoke() }
        }
        rule.waitForIdle()
        assertEquals(FileLinkFixtures.OPENED.sorted(), opened.sorted())
    }
}
