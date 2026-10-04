package com.tether.app.ui.draft

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag as testTagProperty
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.GitHubIssue
import com.tether.app.client.GitHubLabel
import com.tether.app.client.GitHubPullRequest
import com.tether.app.client.LabelText
import com.tether.app.ui.chat.ControlPill
import com.tether.app.ui.chat.GitHubWorkCopy
import com.tether.app.ui.chat.LocalLinkOpener
import com.tether.app.ui.chat.openChatLink
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogSurface
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.putOnClipboard
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/*
 * T8.4: components/github-work-dialog.tsx (tether 90fbb9f), mounted in draft-composer.tsx :347-355
 * beside the worktree select: the "GitHub issues" and "Pull requests" buttons, and the one two-tab
 * dialog they open ([GitHubWorkController] holds its state). A row's menu: "Work on this issue" /
 * "Review this PR" (the composer takes the prompt and the folder), "View on GitHub" (the chat links'
 * path: a Custom Tab), "Copy prompt" (the clipboard, the prompt exactly). When the server names gh
 * authentication as the cause, "Set up GitHub connection" closes the dialog and opens Settings on the
 * GitHub connection (ta-coik.21). Server text (titles, labels, branches) is drawn by the label rule.
 */

/** Test tags of the buttons and the dialog. */
object GitHubWorkTags {
    const val IssuesButton = "draft-github-issues"
    const val PullRequestsButton = "draft-github-prs"
    const val Dialog = "github-work-dialog"
    const val Close = "github-work-close"
    const val Footer = "github-work-footer-close"
    const val Current = "github-work-current"
    const val Panel = "github-work-panel"
    const val Retry = "github-work-retry"
    const val SetUp = "github-work-setup"
    const val Work = "github-work-work"
    const val View = "github-work-view"
    const val Copy = "github-work-copy"
    fun tab(tab: GitHubWorkTab) = "github-work-tab:${tab.name}"
    fun row(number: Long) = "github-work-row:$number"
}

/** draft-composer.tsx :347-355 / github-work-dialog.tsx :171-193: the two buttons, `disabled={disabled || !cwd}`. */
@Composable
internal fun GitHubWorkButtons(cwd: String, creating: Boolean, onOpen: (GitHubWorkTab) -> Unit) {
    val enabled = !creating && cwd.isNotEmpty()
    ControlPill(
        label = GitHubWorkCopy.ISSUES_BUTTON,
        enabled = enabled,
        contentDescription = GitHubWorkCopy.ISSUES_BUTTON,
        onClick = { onOpen(GitHubWorkTab.Issues) },
        icon = TetherIcons.CircleDot,
        chevron = false,
        role = Role.Button,
        testTag = GitHubWorkTags.IssuesButton,
    )
    ControlPill(
        label = GitHubWorkCopy.PULL_REQUESTS_BUTTON,
        enabled = enabled,
        contentDescription = GitHubWorkCopy.PULL_REQUESTS_BUTTON,
        onClick = { onOpen(GitHubWorkTab.PullRequests) },
        icon = TetherIcons.GitPullRequest,
        chevron = false,
        role = Role.Button,
        testTag = GitHubWorkTags.PullRequestsButton,
    )
}

/** The modal dialog (`.folder-dialog`, `showModal()`): Back, the scrim, × and Close close it. */
@Composable
fun GitHubWorkDialog(c: GitHubWorkController, onWork: (GitHubWork) -> Unit, onSetUp: (() -> Unit)?) {
    if (!c.open) return
    TetherDialog(onDismiss = c::close, footer = { GitHubWorkFooter(c) }) {
        GitHubWorkBody(c, onWork, onSetUp)
    }
}

/** The dialog drawn in place over the skin's scrim (the goldens; the modal hosts the same surface). */
@Composable
internal fun GitHubWorkDialogFrame(c: GitHubWorkController, onWork: (GitHubWork) -> Unit = {}, onSetUp: (() -> Unit)? = {}) {
    val t = LocalTetherTokens.current
    Box(Modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
        TetherDialogSurface(footer = { GitHubWorkFooter(c) }) { GitHubWorkBody(c, onWork, onSetUp) }
    }
}

@Composable
private fun RowScope.GitHubWorkFooter(c: GitHubWorkController) {
    TetherKey(onClick = c::close, classes = KeyClasses.ButtonSecondary, label = GitHubWorkCopy.CLOSE, modifier = Modifier.testTag(GitHubWorkTags.Footer))
}

/** :204-386: the header, the tabs, the current repository (or folder) and the tab's panel. */
@Composable
private fun ColumnScope.GitHubWorkBody(c: GitHubWorkController, onWork: (GitHubWork) -> Unit, onSetUp: (() -> Unit)?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().testTag(GitHubWorkTags.Dialog).semantics { paneTitle = GitHubWorkCopy.DIALOG_TITLE }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                GitHubWorkCopy.DIALOG_TITLE,
                color = t.white,
                style = type.body.copy(fontSize = 22.sp, fontWeight = FontWeight(700), letterSpacing = (-0.025).em, lineHeight = 1.3.em),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TetherKey(
                onClick = c::close,
                classes = KeyClasses.IconButton,
                icon = TetherIcons.X,
                iconSize = 19.dp,
                contentDescription = GitHubWorkCopy.CLOSE,
                modifier = Modifier.testTag(GitHubWorkTags.Close),
            )
        }
        Box(Modifier.padding(top = t.css.spaceSm)) { GitHubTabs(c) }
        val issues = c.tab == GitHubWorkTab.Issues
        val repository = if (issues) c.issues.response?.repository else c.pullRequests.response?.repository
        // :226-229 `.folder-current`: the repository once known, else the folder.
        Row(
            Modifier.fillMaxWidth().padding(vertical = t.css.spaceMd).testTag(GitHubWorkTags.Current),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.CircleDot, contentDescription = null, tint = t.muted, modifier = Modifier.size(17.dp))
            Text(
                codeLabel(repository ?: c.cwd),
                color = t.muted,
                style = type.mono.let { androidx.compose.ui.text.TextStyle(fontFamily = it, fontSize = 11.2.sp) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = if (issues) GitHubWorkCopy.PANEL_ISSUES else GitHubWorkCopy.PANEL_PULL_REQUESTS }
                .testTag(GitHubWorkTags.Panel),
        ) {
            if (issues) IssuesPanel(c, onWork, onSetUp) else PullRequestsPanel(c, onWork, onSetUp)
        }
    }
}

/** :207-224 `.settings-tabs` (role=tablist, "GitHub sections"). */
@Composable
private fun GitHubTabs(c: GitHubWorkController) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    Row(
        Modifier
            .fillMaxWidth()
            .cssSurface(shape, t.graphite, CssBorder(1.dp, t.lineStrong), emptyList())
            .semantics { contentDescription = GitHubWorkCopy.TABS_LABEL }
            .selectableGroup()
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GitHubWorkTab.entries.forEach { tab ->
            val active = tab == c.tab
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .background(if (active) t.violetWash else Color.Transparent, RoundedCornerShape(8.dp))
                    .selectable(
                        selected = active,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { c.selectTab(tab) },
                    )
                    .testTag(GitHubWorkTags.tab(tab)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    color = if (active) t.violetStrong else t.muted,
                    maxLines = 1,
                    style = type.ui.let { androidx.compose.ui.text.TextStyle(fontFamily = it, fontSize = 13.sp, fontWeight = FontWeight(650)) },
                )
            }
        }
    }
}

@Composable
private fun IssuesPanel(c: GitHubWorkController, onWork: (GitHubWork) -> Unit, onSetUp: (() -> Unit)?) {
    val state = c.issues
    val response = state.response
    val repository = response?.repository
    when {
        state.loading -> Note(GitHubWorkCopy.LOADING_ISSUES)
        state.error.isNotEmpty() -> ErrorState(state.error, c, onSetUp)
        response == null -> Unit
        repository == null -> EmptyNote(TetherIcons.CircleDot, GitHubWorkCopy.NO_REPO)
        response.issues.isEmpty() -> EmptyNote(TetherIcons.Check, GitHubWorkCopy.noOpenIssues(repository))
        else -> response.issues.forEach { issue ->
            WorkRow(
                number = issue.number,
                rowLabel = GitHubWorkCopy.issueRow(issue.number),
                draft = false,
                title = issue.title,
                description = labelsLine(issue.labels).ifEmpty { GitHubWorkCopy.NO_LABELS },
                menuLabel = GitHubWorkCopy.issueActions(issue.number),
                workLabel = GitHubWorkCopy.WORK_ON_ISSUE,
                url = "https://github.com/$repository/issues/${issue.number}",
                c = c,
                onWork = { c.workOn(issue)?.let(onWork) },
                prompt = { c.promptFor(issue) },
            )
        }
    }
}

@Composable
private fun PullRequestsPanel(c: GitHubWorkController, onWork: (GitHubWork) -> Unit, onSetUp: (() -> Unit)?) {
    val state = c.pullRequests
    val response = state.response
    val repository = response?.repository
    when {
        state.loading -> Note(GitHubWorkCopy.LOADING_PULL_REQUESTS)
        state.error.isNotEmpty() -> ErrorState(state.error, c, onSetUp)
        response == null -> Unit
        repository == null -> EmptyNote(TetherIcons.GitPullRequest, GitHubWorkCopy.NO_REPO)
        response.pullRequests.isEmpty() -> EmptyNote(TetherIcons.Check, GitHubWorkCopy.noOpenPullRequests(repository))
        else -> response.pullRequests.forEach { pr ->
            val branches = if (pr.headRefName.isNotEmpty() && pr.baseRefName.isNotEmpty()) {
                "${LabelText.label(pr.headRefName)} → ${LabelText.label(pr.baseRefName)}"
            } else {
                GitHubWorkCopy.UNKNOWN_BRANCH
            }
            val labels = labelsLine(pr.labels)
            WorkRow(
                number = pr.number,
                rowLabel = GitHubWorkCopy.pullRequestRow(pr.number),
                draft = pr.isDraft,
                title = pr.title,
                description = if (labels.isNotEmpty()) "$branches · $labels" else branches,
                menuLabel = GitHubWorkCopy.pullRequestActions(pr.number),
                workLabel = GitHubWorkCopy.REVIEW_PR,
                url = "https://github.com/$repository/pull/${pr.number}",
                c = c,
                onWork = { c.review(pr)?.let(onWork) },
                prompt = { c.promptFor(pr) },
            )
        }
    }
}

/** :278/:349 `labels.map(name).join(" · ")`, each name by the label rule. */
internal fun labelsLine(labels: List<GitHubLabel>): String = labels.joinToString(" · ") { LabelText.label(it.name) }

/** `.model-browser-empty` with the loading words (:231-235). */
@Composable
private fun Note(text: String) {
    val t = LocalTetherTokens.current
    Text(
        text,
        color = t.muted,
        textAlign = TextAlign.Center,
        style = LocalTetherTypography.current.body.copy(fontSize = 12.48.sp),
        modifier = Modifier.fillMaxWidth().padding(vertical = t.css.spaceXl).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** `.model-browser-empty` with its glyph (:257-269, :328-340). */
@Composable
private fun EmptyNote(icon: ImageVector, text: String) {
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = t.css.spaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(icon, contentDescription = null, tint = t.faint, modifier = Modifier.size(24.dp))
        Text(text, color = t.muted, textAlign = TextAlign.Center, style = LocalTetherTypography.current.body.copy(fontSize = 12.48.sp))
    }
}

/** :237-255: the words, "Try again", and "Set up GitHub connection" when they name gh authentication. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorState(error: String, c: GitHubWorkController, onSetUp: (() -> Unit)?) {
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = t.css.spaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.css.spaceMd),
    ) {
        Text(
            error,
            color = t.muted,
            textAlign = TextAlign.Center,
            style = LocalTetherTypography.current.body.copy(fontSize = 12.48.sp),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
            TetherKey(onClick = c::retry, classes = KeyClasses.ButtonSecondary, label = GitHubWorkCopy.TRY_AGAIN, modifier = Modifier.testTag(GitHubWorkTags.Retry))
            if (onSetUp != null && GitHubWorkCopy.needsSetup(error)) {
                TetherKey(
                    onClick = {
                        c.close()
                        onSetUp()
                    },
                    classes = KeyClasses.ButtonPrimary,
                    label = GitHubWorkCopy.SET_UP,
                    icon = TetherIcons.Settings,
                    iconSize = 13.dp,
                    modifier = Modifier.testTag(GitHubWorkTags.SetUp),
                )
            }
        }
    }
}

/** :271-322 / :342-383: a row (`#n · title` over its description) and, when tapped, its menu. */
@Composable
private fun WorkRow(
    number: Long,
    rowLabel: String,
    draft: Boolean,
    title: String,
    description: String,
    menuLabel: String,
    workLabel: String,
    url: String,
    c: GitHubWorkController,
    onWork: () -> Unit,
    prompt: () -> String?,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val open = c.openMenu == number
    val heading = "#$number · ${LabelText.title(title)}"
    val toggle = { c.toggleMenu(number) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (open) t.graphiteRaised else Color.Transparent, RoundedCornerShape(t.radiusMd))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = toggle)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = "$rowLabel, $heading, $description"
                stateDescription = if (open) "Expanded" else "Collapsed"
                onClick(rowLabel) { toggle(); true }
                testTagProperty = GitHubWorkTags.row(number)
            }
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                if (draft) Icon(TetherIcons.GitPullRequestDraft, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
                Text(heading, color = t.white, style = type.body.copy(fontSize = 13.12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(description, color = t.faint, style = type.body.copy(fontSize = 11.2.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (open) WorkMenu(number, menuLabel, workLabel, url, c, onWork, prompt)
}

/** The row's actions (`.model-browser-list`): work / review, View on GitHub, Copy prompt. */
@Composable
private fun WorkMenu(number: Long, menuLabel: String, workLabel: String, url: String, c: GitHubWorkController, onWork: () -> Unit, prompt: () -> String?) {
    val t = LocalTetherTokens.current
    val context = LocalContext.current
    val opener = LocalLinkOpener.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = t.css.spaceMd, top = t.css.spaceXs, bottom = t.css.spaceSm)
            .semantics { contentDescription = menuLabel },
    ) {
        MenuOption(TetherIcons.Play, workLabel, selected = true, tag = GitHubWorkTags.Work, onClick = onWork)
        MenuOption(TetherIcons.ExternalLink, GitHubWorkCopy.VIEW_ON_GITHUB, tag = GitHubWorkTags.View, role = Role.Button) {
            openChatLink(context, opener, url, t.graphite)
        }
        val copied = c.copiedNumber == number
        val failed = c.copyErrorNumber == number
        val copyLabel = when {
            failed -> GitHubWorkCopy.COPY_FAILED
            copied -> GitHubWorkCopy.COPIED_PROMPT
            else -> GitHubWorkCopy.COPY_PROMPT
        }
        MenuOption(if (copied) TetherIcons.Check else TetherIcons.Copy, copyLabel, tag = GitHubWorkTags.Copy, live = true) {
            val text = prompt() ?: return@MenuOption
            c.copied(number, putOnClipboard(context, text, "prompt"))
        }
    }
}

/** `.tether-select-option` (`is-selected` on the first): a glyph and the words, 44dp. */
@Composable
private fun MenuOption(
    icon: ImageVector,
    label: String,
    tag: String,
    selected: Boolean = false,
    live: Boolean = false,
    role: Role = Role.Button,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (selected) t.violetWash else Color.Transparent, RoundedCornerShape(t.radiusSm))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = role, onClick = onClick)
            .clearAndSetSemantics {
                this.role = role
                contentDescription = label
                if (live) liveRegion = LiveRegionMode.Polite
                onClick(label) { onClick(); true }
                testTagProperty = tag
            }
            .padding(horizontal = t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) t.violetStrong else t.muted, modifier = Modifier.size(13.dp))
        Text(label, color = if (selected) t.violetStrong else t.ink, style = LocalTetherTypography.current.body.copy(fontSize = 12.8.sp))
    }
}
