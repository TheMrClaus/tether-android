package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult

/** Shared readers for the phone drawer's tests (ta-1jj7, owner-directed design). */

/** The text layouts, from the unmerged tree, whose text is [full] or a cut of it (a row's texts sit under clearAndSetSemantics). */
internal fun ComposeContentTestRule.textLayoutsOf(full: String): List<TextLayoutResult> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node -> node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { a -> ArrayList<TextLayoutResult>().also { a.invoke(it) }.firstOrNull() } }
        .filter { l -> l.layoutInput.text.text.let { t -> t.length > 8 && full.startsWith(t.removeSuffix("…")) } }

/** The unmerged text node whose text is exactly [text]. */
internal fun ComposeContentTestRule.textNode(text: String): SemanticsNode =
    onAllNodes(SemanticsMatcher("text $text") { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == text }, useUnmergedTree = true)
        .fetchSemanticsNodes().first()

internal fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

internal fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

internal fun SemanticsNode.description(): String? = config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

/** Every row's outer node (tag `sidebar-row:`), from the unmerged tree. */
internal fun ComposeContentTestRule.rowNodes(): List<SemanticsNode> =
    onAllNodes(SemanticsMatcher("a session row") { it.tag()?.startsWith("sidebar-row:") == true }, useUnmergedTree = true).fetchSemanticsNodes()
