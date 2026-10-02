package com.tether.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tether.app.client.ClaudeAccount
import com.tether.app.client.ClaudeLoginStatus
import com.tether.app.client.ClaudeSyncMode
import com.tether.app.client.LabelText
import com.tether.app.client.TextCut
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSelect
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.appendStyled
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/** Tags of the Claude accounts section. */
object ClaudeAccountsTags {
    const val Section = "claude-accounts"
    const val OwnerNeeded = "claude-accounts-owner-needed"
    const val TryAgain = "claude-accounts-try-again"
    const val Loading = "claude-accounts-loading"
    const val Notice = "claude-accounts-notice"
    const val Retry = "claude-accounts-retry"
    const val Add = "claude-accounts-add"
    const val AddField = "claude-accounts-add-field"
    const val AddSubmit = "claude-accounts-add-submit"
    const val AddCancel = "claude-accounts-add-cancel"
    const val Line = "claude-accounts-line"
    const val Sync = "claude-accounts-sync"
    const val SyncMode = "claude-accounts-sync-mode"
    const val SyncPrimary = "claude-accounts-sync-primary"
    const val SyncNow = "claude-accounts-sync-now"
    const val SyncLine = "claude-accounts-sync-line"
    const val ConfirmSheet = "claude-accounts-confirm"
    const val ConfirmCancel = "claude-accounts-confirm-cancel"
    const val ConfirmGo = "claude-accounts-confirm-go"
    fun syncCategory(key: String) = "claude-accounts-sync-category:$key"
    fun card(id: String) = "claude-account:$id"
    fun plan(id: String) = "claude-account-plan:$id"
    fun organization(id: String) = "claude-account-org:$id"
    fun status(id: String) = "claude-account-status:$id"
    fun id(id: String) = "claude-account-id:$id"
    fun path(id: String) = "claude-account-path:$id"
    fun check(id: String) = "claude-account-check:$id"
    fun rename(id: String) = "claude-account-rename:$id"
    fun renameField(id: String) = "claude-account-rename-field:$id"
    fun renameSave(id: String) = "claude-account-rename-save:$id"
    fun renameCancel(id: String) = "claude-account-rename-cancel:$id"
    fun login(id: String) = "claude-account-login:$id"
    fun logout(id: String) = "claude-account-logout:$id"
    fun remove(id: String) = "claude-account-remove:$id"
    fun deleteCredentials(id: String) = "claude-account-delete-credentials:$id"
    fun cardLine(id: String) = "claude-account-line:$id"
    fun loginPanel(id: String) = "claude-account-login-panel:$id"
    fun loginStatus(id: String) = "claude-account-login-status:$id"
    fun loginCancel(id: String) = "claude-account-login-cancel:$id"
    fun loginOpen(id: String) = "claude-account-login-open:$id"
    fun loginLinkRefused(id: String) = "claude-account-login-refused:$id"
    fun code(id: String) = "claude-account-code:$id"
    fun codeSubmit(id: String) = "claude-account-code-submit:$id"
}

/**
 * settings-dialog.tsx `ClaudeAccountsSection` (887c222 :1380-1867) with its changes (ta-7rh), wired to
 * [binding] through one [ClaudeAccountsController] per (source, changes, server):
 * - read when the Engines tab opens (this is composed only then) and when the server changes;
 *   Retry and every change read again; one plan re-read [ClaudeAccountsModel.PLAN_RETRY_MS] after a
 *   first paint from the offline snapshot, once per opening (the web's `planRetriedRef`);
 * - Check reads one account's status; the sync state is read once two accounts are listed;
 * - Add, Rename, Log in (link and code), Log out, Remove and the sync settings as on the web, Log out
 *   and Remove asked first ([ClaudeAccountsConfirmDialog]);
 * - every answer is bound to [ClaudeAccountsBinding.origin] and every change sent for it only;
 *   changing server or closing the tab cancels what is in flight, ends each login's poll and drops
 *   any pasted code.
 */
@Composable
internal fun ClaudeAccountsHost(binding: ClaudeAccountsBinding, narrow: Boolean) {
    val current = binding.origin
    val source = binding.source
    val actions = binding.actions
    // A seed counts only for the server it was built for.
    val seed = binding.initial?.takeIf { current != null && it.origin == current }
    val parent = rememberCoroutineScope()
    val c = remember(source, actions, current) {
        ClaudeAccountsController(source, actions, current, parent, seed, binding.writeSeed?.takeIf { seed != null }, binding.pace)
    }
    DisposableEffect(c) { onDispose { c.dispose() } }
    var planRetried by remember(c) { mutableStateOf(false) }

    LaunchedEffect(c, c.reloads) {
        if (current == null || (seed != null && c.reloads == 0)) return@LaunchedEffect
        c.state = ClaudeAccountsModel.foldList(c.state, source.list(), current)
        if (!planRetried && c.state.listFault == null && ClaudeAccountsModel.wantsPlanRetry(c.state.accounts)) {
            planRetried = true
            delay(ClaudeAccountsModel.PLAN_RETRY_MS)
            c.state = ClaudeAccountsModel.foldList(c.state, source.list(), current)
        }
    }
    val wantsSync = ClaudeAccountsModel.wantsSync(c.state)
    LaunchedEffect(c, wantsSync) {
        if (current == null || seed != null || !wantsSync || c.state.sync != null) return@LaunchedEffect
        c.state = ClaudeAccountsModel.foldSync(c.state, source.sync(), current)
    }

    ClaudeAccountsSection(c, ClaudeAccountsPresentation.view(c.state, binding.timeOf), narrow, binding.opener)
    c.confirm?.let { shown ->
        ClaudeAccountsConfirmDialog(shown, onCancel = c::dismissConfirm, onConfirm = { c.confirmed(shown) })
    }
}

/** `new Date(ranAt).toLocaleTimeString()`. */
internal fun localTime(at: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(at))

/** The section over [view] (the reads) and [c] (the changes' state). */
@Composable
private fun ClaudeAccountsSection(
    c: ClaudeAccountsController,
    view: ClaudeAccountsPresentation.View,
    narrow: Boolean,
    opener: LoginLinkOpener,
) {
    SettingsSection(
        ClaudeAccountsPresentation.TITLE,
        AnnotatedString(ClaudeAccountsPresentation.INTRO),
        narrow,
        modifier = Modifier.testTag(ClaudeAccountsTags.Section),
    ) {
        if (c.ownerNeeded) OwnerNeeded(c)
        when {
            view.notice != null -> ListNotice(view.notice, narrow, c::reload)
            view.loading -> {
                RowRule()
                Text(
                    ClaudeAccountsPresentation.LOADING,
                    color = LocalTetherTokens.current.muted,
                    style = settingsText(LocalTetherTypography.current.ui, 12f, 400, lineHeight = 1.6f),
                    modifier = Modifier.testTag(ClaudeAccountsTags.Loading).padding(vertical = 17.dp),
                )
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 16.dp else 20.dp)) {
                val accounts = c.state.accounts.orEmpty().associateBy { it.id }
                view.cards.forEach { card -> accounts[card.id]?.let { AccountCard(card, it, c, narrow, opener) } }
            }
        }
        AddRow(c, narrow, topGap = view.cards.isNotEmpty())
        c.line?.let { NoteLine(it, ClaudeAccountsTags.Line) }
        view.sync?.let { SyncRows(it, c, narrow) }
    }
}

/**
 * The owner-grade explanation (T10.4's pattern, first used here): a quiet note with the lock glyph,
 * read as one sentence. Every control it covers is drawn disabled.
 */
@Composable
internal fun OwnerGradeNote(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { }
            .cssSurface(RoundedCornerShape(8.dp), t.mineral, null, emptyList())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(TetherIcons.LockKeyhole, contentDescription = null, tint = t.muted, modifier = Modifier.padding(top = 3.dp).size(14.dp))
        Text(text, color = t.muted, style = settingsText(type.ui, 13f, 400, lineHeight = 1.6f))
    }
}

/** ta-7rh: a server without #236 refused a change with its owner sign-in sentence: said once, with Try again. */
@Composable
private fun OwnerNeeded(c: ClaudeAccountsController) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OwnerGradeNote(ClaudeAccountsCopy.OWNER_NEEDED, Modifier.testTag(ClaudeAccountsTags.OwnerNeeded).semantics { liveRegion = LiveRegionMode.Polite })
        TetherKey(
            onClick = c::retryOwner,
            classes = KeyClasses.ButtonSecondary,
            label = ClaudeAccountsCopy.TRY_AGAIN,
            icon = TetherIcons.RefreshCw,
            iconSize = 14.dp,
            modifier = Modifier.testTag(ClaudeAccountsTags.TryAgain),
        )
    }
}

/** settings-dialog.tsx:1666-1670 (the Status row with Retry), or the native blocked notice. */
@Composable
private fun ListNotice(notice: ClaudeAccountsPresentation.Notice, narrow: Boolean, onRetry: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    SettingsRow(
        narrow = narrow,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        text = { m ->
            if (!notice.blocked) {
                SettingsRowText("Status", AnnotatedString(notice.text), m.testTag(ClaudeAccountsTags.Notice))
            } else {
                Row(m.testTag(ClaudeAccountsTags.Notice).semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(TetherIcons.ShieldAlert, contentDescription = null, tint = t.muted, modifier = Modifier.padding(top = 3.dp).size(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(notice.text, color = t.ink, style = settingsText(type.ui, 14f, 650, lineHeight = 1.5f))
                        notice.detail?.let { Text(it, color = t.muted, style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f)) }
                    }
                }
            }
        },
        control = { m ->
            TetherKey(
                onClick = onRetry,
                classes = KeyClasses.ButtonSecondary,
                label = "Retry",
                icon = TetherIcons.RefreshCw,
                iconSize = 14.dp,
                modifier = m.testTag(ClaudeAccountsTags.Retry),
            )
        },
    )
}

/**
 * One `.engine-card` (settings-dialog.tsx:1680-1836; studio.css 638-645): the C glyph, the title
 * with its plan and Pre-existing tags, the status and organization lines, Rename and Check; then the
 * rename row, the Sign-in row (the CLAUDE_CONFIG_DIR, by the path rule) with the login panel, and
 * the Remove row. On a phone the keys drop under the title so the title keeps the width.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCard(card: ClaudeAccountsPresentation.Card, account: ClaudeAccount, c: ClaudeAccountsController, narrow: Boolean, opener: LoginLinkOpener) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val id = card.id
    val can = c.canChange && account.checkable
    val renaming = c.renaming == id
    val login = c.logins[id]
    Column(
        Modifier
            .testTag(ClaudeAccountsTags.card(id))
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(12.dp), t.graphite, CssBorder(1.dp, t.line), emptyList())
            .padding(if (narrow) 16.dp else 20.dp),
    ) {
        val keys: @Composable () -> Unit = {
            if (card.canRename) {
                TetherKey(
                    onClick = { if (renaming) c.cancelRename() else c.startRename(account) },
                    enabled = renaming || can,
                    classes = KeyClasses.ButtonSecondary,
                    label = "Rename",
                    icon = TetherIcons.Pencil,
                    iconSize = 14.dp,
                    contentDescription = "Rename ${card.title}",
                    modifier = Modifier.testTag(ClaudeAccountsTags.rename(id)),
                )
            }
            TetherKey(
                onClick = { c.check(id) },
                enabled = card.canCheck,
                classes = KeyClasses.ButtonSecondary,
                label = "Check",
                icon = if (card.checking) TetherIcons.Loader else TetherIcons.RefreshCw,
                iconSize = 14.dp,
                contentDescription = "Check ${card.title}",
                modifier = Modifier.testTag(ClaudeAccountsTags.check(id)),
            )
        }
        Row(verticalAlignment = if (narrow) Alignment.Top else Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // `.provider-glyph.provider-claude` with the letter C (aria-hidden), Studio's raised square.
            Box(
                Modifier.size(32.dp).background(t.graphiteRaised, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("C", color = t.ink, style = settingsText(type.mono, 12.8f, 750))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        card.title,
                        color = t.white,
                        style = settingsText(type.ui, 15f, 700, lineHeight = 1.4f),
                        modifier = Modifier.semantics { heading() }.align(Alignment.CenterVertically),
                    )
                    card.planTag?.let {
                        Tag(it, Modifier.testTag(ClaudeAccountsTags.plan(id)).align(Alignment.CenterVertically).semantics { contentDescription = "Plan: $it" })
                    }
                    if (card.preExisting) {
                        Tag(ClaudeAccountsPresentation.PRE_EXISTING, Modifier.align(Alignment.CenterVertically).semantics { contentDescription = ClaudeAccountsPresentation.PRE_EXISTING_TIP })
                    }
                }
                card.idLine?.let { line ->
                    // r2: this title could pass for another's: the profile id tells them apart.
                    Text(
                        accountIdText(line),
                        style = settingsText(type.mono, 11.5f, 400, lineHeight = 1.5f),
                        color = t.faint,
                        modifier = Modifier
                            .testTag(ClaudeAccountsTags.id(id))
                            .semantics { contentDescription = "profile ${LabelText.visibleValue(line)}" },
                    )
                }
                Text(
                    card.status,
                    color = t.muted,
                    style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f),
                    modifier = Modifier.testTag(ClaudeAccountsTags.status(id)).semantics { liveRegion = LiveRegionMode.Polite },
                )
                card.organization?.let {
                    Text(
                        it,
                        color = t.muted,
                        style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f),
                        modifier = Modifier.testTag(ClaudeAccountsTags.organization(id)),
                    )
                }
            }
            if (!narrow) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { keys() }
        }
        if (narrow) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { keys() }
        // `.engine-card-body`: a rule, then the rows (the first without its own rule).
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(t.line))
        if (renaming) {
            CardRow(narrow = narrow, title = ClaudeAccountsCopy.NICKNAME, caption = AnnotatedString(ClaudeAccountsCopy.RENAME_CAPTION), first = true, stack = true) {
                val saving = c.busy == AccountsAction.Rename && c.busyId == id
                AccountField(
                    value = c.renameText,
                    onChange = c::editRename,
                    placeholder = ClaudeAccountsCopy.PLACEHOLDER,
                    description = "New nickname for ${card.title}",
                    tag = ClaudeAccountsTags.renameField(id),
                    narrow = narrow,
                    enabled = !saving,
                    focusOnStart = true,
                    onDone = { c.submitRename() },
                    modifier = Modifier.weight(1f),
                )
                TetherKey(
                    onClick = { c.submitRename() },
                    enabled = can && c.renameText.isNotBlank(),
                    classes = KeyClasses.ButtonSecondary,
                    label = if (saving) ClaudeAccountsCopy.SAVING else ClaudeAccountsCopy.SAVE,
                    modifier = Modifier.testTag(ClaudeAccountsTags.renameSave(id)),
                )
                TetherKey(onClick = c::cancelRename, classes = KeyClasses.ButtonSecondary, label = ClaudeAccountsCopy.CANCEL, modifier = Modifier.testTag(ClaudeAccountsTags.renameCancel(id)))
            }
        }
        CardRow(
            narrow = narrow,
            title = "Sign-in",
            caption = card.configDir?.let { codeLabel(it) } ?: AnnotatedString(ClaudeAccountsPresentation.NO_HOME),
            captionTag = ClaudeAccountsTags.path(id),
            first = !renaming,
        ) {
            val loggingOut = c.busy == AccountsAction.Logout && c.busyId == id
            ChangeKey(ClaudeAccountsCopy.LOG_IN, TetherIcons.LogIn, "Log in ${card.title}", ClaudeAccountsTags.login(id), enabled = can && login == null) { c.startLogin(account) }
            ChangeKey(if (loggingOut) ClaudeAccountsCopy.LOGGING_OUT else ClaudeAccountsCopy.LOG_OUT, TetherIcons.LogOut, "Log out ${card.title}", ClaudeAccountsTags.logout(id), enabled = can) {
                c.askLogout(account, card.title)
            }
        }
        login?.let { LoginRows(card, it, c, narrow, opener) }
        CardRow(narrow = narrow, title = "Remove account", caption = AnnotatedString("Deletes the profile entry from Tether")) {
            val removing = c.busy == AccountsAction.Remove && c.busyId == id
            AccountCheckbox(
                label = ClaudeAccountsCopy.ALSO_DELETE,
                checked = c.deleteCredentials[id] == true,
                enabled = can,
                description = "Also delete stored login for ${card.title}",
                tag = ClaudeAccountsTags.deleteCredentials(id),
                onChange = { c.setDeleteCredentials(id, it) },
            )
            ChangeKey(if (removing) ClaudeAccountsCopy.REMOVING else ClaudeAccountsCopy.REMOVE, TetherIcons.Trash2, "Remove ${card.title}", ClaudeAccountsTags.remove(id), enabled = can) {
                c.askRemove(account, card.title)
            }
        }
        c.lines[id]?.let { NoteLine(it, ClaudeAccountsTags.cardLine(id)) }
    }
}

/**
 * settings-dialog.tsx:1745-1792, the login panel: what it is waiting for and Cancel; the link (its
 * host said, opened in the phone's browser); the code field and Submit until it ends.
 */
@Composable
private fun LoginRows(card: ClaudeAccountsPresentation.Card, login: LoginPanel, c: ClaudeAccountsController, narrow: Boolean, opener: LoginLinkOpener) {
    val id = card.id
    var unopened by remember(login.link) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag(ClaudeAccountsTags.loginPanel(id))) {
        val status = when {
            login.error != null && login.status == ClaudeLoginStatus.Error -> login.error
            login.starting -> ClaudeAccountsCopy.LOGIN_STARTING
            login.status == ClaudeLoginStatus.PendingUrl -> ClaudeAccountsCopy.LOGIN_WAITING
            login.status == ClaudeLoginStatus.AwaitingCode -> ClaudeAccountsCopy.LOGIN_PASTE
            login.status == ClaudeLoginStatus.Success -> ClaudeAccountsCopy.LOGIN_SIGNED_IN
            login.status == ClaudeLoginStatus.Error -> ClaudeAccountsCopy.LOGIN_FAILED
            else -> ClaudeAccountsCopy.LOGIN_STARTING
        }
        CardRow(narrow = narrow, title = ClaudeAccountsCopy.LOGIN_TITLE, caption = AnnotatedString(status), captionTag = ClaudeAccountsTags.loginStatus(id), live = true) {
            TetherKey(onClick = { c.cancelLogin(id) }, classes = KeyClasses.ButtonSecondary, label = ClaudeAccountsCopy.CANCEL, contentDescription = "Cancel login for ${card.title}", modifier = Modifier.testTag(ClaudeAccountsTags.loginCancel(id)))
        }
        login.link?.let { link ->
            CardRow(narrow = narrow, title = ClaudeAccountsCopy.OPEN_TITLE, caption = AnnotatedString(ClaudeAccountsCopy.openCaption(LabelText.visibleValue(link.host)))) {
                TetherKey(
                    onClick = { unopened = !opener.open(link) },
                    classes = KeyClasses.ButtonSecondary,
                    label = ClaudeAccountsCopy.OPEN,
                    icon = TetherIcons.ExternalLink,
                    iconSize = 14.dp,
                    contentDescription = "Open the sign-in link in the browser",
                    modifier = Modifier.testTag(ClaudeAccountsTags.loginOpen(id)),
                )
            }
            if (unopened) NoteLine(AccountsLine(ClaudeAccountsCopy.LINK_UNOPENED, error = true), ClaudeAccountsTags.loginOpen(id) + ":unopened")
        }
        if (login.link == null && login.linkRefused) {
            NoteLine(AccountsLine(ClaudeAccountsCopy.LINK_REFUSED, error = true), ClaudeAccountsTags.loginLinkRefused(id))
        }
        if (login.status != ClaudeLoginStatus.Error && login.status != ClaudeLoginStatus.Success) {
            CardRow(narrow = narrow, title = ClaudeAccountsCopy.CODE_TITLE, caption = AnnotatedString(ClaudeAccountsCopy.CODE_CAPTION), stack = true) {
                val submitting = c.busy == AccountsAction.Code && c.busyId == id
                val code = c.codes[id].orEmpty()
                AccountField(
                    value = code,
                    onChange = { c.editCode(id, it) },
                    placeholder = ClaudeAccountsCopy.CODE_PLACEHOLDER,
                    description = "${card.title} authorization code",
                    tag = ClaudeAccountsTags.code(id),
                    narrow = narrow,
                    enabled = !submitting,
                    sensitive = true,
                    onDone = { c.submitCode(id) },
                    modifier = Modifier.weight(1f),
                )
                TetherKey(
                    onClick = { c.submitCode(id) },
                    enabled = c.canChange && !login.starting && code.isNotBlank(),
                    classes = KeyClasses.ButtonSecondary,
                    label = if (submitting) ClaudeAccountsCopy.SUBMITTING else ClaudeAccountsCopy.SUBMIT,
                    modifier = Modifier.testTag(ClaudeAccountsTags.codeSubmit(id)),
                )
            }
            login.error?.let { NoteLine(AccountsLine(it, error = true), ClaudeAccountsTags.loginStatus(id) + ":error") }
        }
    }
}

/** settings-dialog.tsx:1838-1862: "Add Claude account", or the nickname row with Add and Cancel. */
@Composable
private fun AddRow(c: ClaudeAccountsController, narrow: Boolean, topGap: Boolean) {
    if (!c.adding) {
        TetherKey(
            onClick = c::openAdd,
            enabled = c.canChange,
            classes = KeyClasses.ButtonSecondary,
            label = ClaudeAccountsCopy.ADD,
            icon = TetherIcons.Plus,
            iconSize = 14.dp,
            modifier = Modifier.padding(top = if (topGap) 20.dp else 0.dp).testTag(ClaudeAccountsTags.Add),
        )
        return
    }
    val adding = c.busy == AccountsAction.Add
    Column(Modifier.padding(top = if (topGap) 8.dp else 0.dp)) {
        CardRow(narrow = narrow, title = ClaudeAccountsCopy.NICKNAME, caption = AnnotatedString(ClaudeAccountsCopy.NICKNAME_CAPTION), stack = true) {
            AccountField(
                value = c.addText,
                onChange = c::editAdd,
                placeholder = ClaudeAccountsCopy.PLACEHOLDER,
                description = ClaudeAccountsCopy.NICKNAME_FIELD,
                tag = ClaudeAccountsTags.AddField,
                narrow = narrow,
                enabled = !adding,
                focusOnStart = true,
                onDone = { c.submitAdd() },
                modifier = Modifier.weight(1f),
            )
            TetherKey(
                onClick = { c.submitAdd() },
                enabled = c.canChange && c.addText.isNotBlank(),
                classes = KeyClasses.ButtonSecondary,
                label = if (adding) ClaudeAccountsCopy.ADDING else ClaudeAccountsCopy.ADD_KEY,
                modifier = Modifier.testTag(ClaudeAccountsTags.AddSubmit),
            )
            TetherKey(onClick = c::cancelAdd, classes = KeyClasses.ButtonSecondary, label = ClaudeAccountsCopy.CANCEL, modifier = Modifier.testTag(ClaudeAccountsTags.AddCancel))
        }
    }
}

/** r2: an id longer than this is cut in the MIDDLE, so its end (a "-2" suffix) always shows (ta-895). */
internal const val ACCOUNT_ID_SHOWN = 96

/** The raw parts of [id] to draw: the whole id, or its head and tail cut at cluster boundaries. */
internal fun accountIdParts(id: String): List<String> {
    if (id.length <= ACCOUNT_ID_SHOWN) return listOf(id)
    val half = (ACCOUNT_ID_SHOWN - 1) / 2
    val headEnd = TextCut.boundaryAtOrBefore(id, half)
    val tailStart = TextCut.boundaryAtOrBefore(id, id.length - half, floor = headEnd)
    return listOf(id.substring(0, headEnd), id.substring(tailStart))
}

/**
 * A profile id as drawn (ta-895's New session picker): the one-line rule ([SafeText.Rule.Line]:
 * hidden code points and line breaks as tokens) in an LTR paragraph, breakable anywhere, never cut
 * at its end.
 */
@Composable
private fun accountIdText(id: String): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(id, t) {
        val style = tokenStyle(t)
        AnnotatedString.Builder(id.length * 2).apply {
            withStyle(ParagraphStyle(textDirection = codeDirection)) {
                accountIdParts(id).forEachIndexed { i, part ->
                    if (i > 0) append("…")
                    appendStyled(SafeText.breakAnywhere(SafeText.encode(part, SafeText.Rule.Line)), style)
                }
            }
        }.toAnnotatedString()
    }
}

/** A change's key: enabled only when a change may start. */
@Composable
private fun ChangeKey(label: String, icon: ImageVector, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    TetherKey(
        onClick = onClick,
        enabled = enabled,
        classes = KeyClasses.ButtonSecondary,
        label = label,
        icon = icon,
        iconSize = 14.dp,
        contentDescription = description,
        modifier = Modifier.testTag(tag),
    )
}

/**
 * `.settings-server-input` for a nickname or the authorization code. Plain `remember`d by the
 * controller, never saved. [sensitive]: the code: the keyboard is told it is a password (no
 * suggestions, no learning), drawn as typed, as the web's text field shows it.
 */
@Composable
private fun AccountField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    description: String,
    tag: String,
    narrow: Boolean,
    enabled: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    sensitive: Boolean = false,
    focusOnStart: Boolean = false,
) {
    val t = LocalTetherTokens.current
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (focusOnStart) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = serverFieldStyle(narrow)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = style.copy(color = t.ink),
        cursorBrush = SolidColor(t.violet),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = if (sensitive) KeyboardType.Password else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .testTag(tag)
            .semantics { contentDescription = description }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner -> ServerFieldBox(enabled, focused, style, if (value.isEmpty()) AnnotatedString(placeholder) else null, inner) },
    )
}

/** `.settings-model-default`'s checkbox and label (the material layer's violet tick); state announced, never colour alone. */
@Composable
private fun AccountCheckbox(label: String, checked: Boolean, enabled: Boolean, description: String, tag: String, onChange: (Boolean) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .testTag(tag)
            .heightIn(min = 44.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onChange,
            )
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .cssSurface(RoundedCornerShape(3.dp), if (checked) t.violetStrong else t.graphite, if (checked) null else CssBorder(1.dp, t.muted), emptyList()),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(TetherIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
        Text(label, color = if (enabled) t.ink else t.faint, style = settingsText(type.ui, 13f, 500, lineHeight = 1.5f))
    }
}

/** A line under a card or the section: the web's `.settings-field-note.is-warning`, or a quiet status (words and glyph, never colour alone). */
@Composable
private fun NoteLine(line: AccountsLine, tag: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .testTag(tag)
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = if (line.error) LiveRegionMode.Assertive else LiveRegionMode.Polite }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(if (line.error) TetherIcons.TriangleAlert else TetherIcons.Check, contentDescription = null, tint = if (line.error) t.warning else t.muted, modifier = Modifier.padding(top = 2.dp).size(13.dp))
        Text(line.text, color = if (line.error) t.warning else t.muted, style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f))
    }
}

/**
 * A `.settings-row` inside a card (`.engine-card-body .settings-row:first-child { border-top: 0 }`).
 * [stack]: a field row: the controls get their own line under the text, on every width.
 */
@Composable
private fun CardRow(
    narrow: Boolean,
    title: String,
    caption: AnnotatedString,
    captionTag: String? = null,
    first: Boolean = false,
    live: Boolean = false,
    stack: Boolean = false,
    keys: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (!first) RowRule()
        val text: @Composable (Modifier) -> Unit = { m ->
            val tagged = if (captionTag != null) m.testTag(captionTag) else m
            SettingsRowText(title, caption, if (live) tagged.semantics { liveRegion = LiveRegionMode.Polite } else tagged)
        }
        if (narrow || stack) {
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                text(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { keys() }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                text(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { keys() }
            }
        }
    }
}

/**
 * A tag after a card's title. The web's `.mode-tag` is hidden under the Studio finish
 * (studio.css:344, a rail rule); the plan name is this section's point (#231), so it is drawn in
 * Studio's own tag idiom (`.passkey-tag`, studio.css: 11px on the mineral wash, no border).
 */
@Composable
private fun Tag(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        color = t.ink,
        maxLines = 1,
        style = settingsText(type.ui, 11f, 600, lineHeight = 1.4f),
        modifier = modifier.background(t.mineral, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private val SYNC_CATEGORIES = listOf("plugins" to "Plugins", "skills" to "Skills", "mcp" to "MCP servers", "hooks" to "Hooks")

/**
 * ClaudeAccountSyncSection (settings-dialog.tsx:1241-1376): the mode, the categories when it is
 * "selected", the primary account when it syncs, the warning, the status with Sync now. Each change
 * saves the whole config at once, as on the web. A mode this client does not know is shown, never
 * written back.
 */
@Composable
private fun SyncRows(sync: ClaudeAccountsPresentation.SyncView, c: ClaudeAccountsController, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shown = c.state.sync ?: return
    val config = shown.config
    val editable = c.canChange && config.mode != ClaudeSyncMode.Unknown
    val accounts = c.state.accounts.orEmpty()
    Column(Modifier.testTag(ClaudeAccountsTags.Sync).padding(top = 20.dp)) {
        SettingsRow(
            narrow = narrow,
            text = { m -> SettingsRowText("Sync across accounts", AnnotatedString("Share plugins, skills, MCP servers, and hooks between your Claude accounts"), m) },
            control = { m ->
                val modes = listOf(ClaudeSyncMode.All, ClaudeSyncMode.Selected, ClaudeSyncMode.None)
                TetherSelect(
                    options = modes.map { TetherSelectOption(it.name, ClaudeAccountsPresentation.modeLabel(it)) },
                    selectedValue = config.mode.name,
                    onSelect = { o -> modes.firstOrNull { it.name == o.value }?.let { c.saveSync(config.copy(mode = it)) } },
                    enabled = editable,
                    placeholder = ClaudeAccountsPresentation.modeLabel(config.mode),
                    // The chosen value is said with the row's name (the trigger's own label is not read out).
                    contentDescription = "Sync across accounts: ${ClaudeAccountsPresentation.modeLabel(config.mode)}",
                    modifier = (if (narrow) m.fillMaxWidth() else m).testTag(ClaudeAccountsTags.SyncMode),
                )
            },
        )
        if (config.mode == ClaudeSyncMode.Selected) {
            SettingsRow(
                narrow = narrow,
                text = { m -> SettingsRowText("Categories", AnnotatedString("Only these are kept in sync"), m) },
                control = { m ->
                    androidx.compose.foundation.layout.FlowRow(m, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SYNC_CATEGORIES.forEach { (key, label) ->
                            val on = when (key) {
                                "plugins" -> config.categories.plugins
                                "skills" -> config.categories.skills
                                "mcp" -> config.categories.mcp
                                else -> config.categories.hooks
                            }
                            AccountCheckbox(
                                label = label,
                                checked = on,
                                enabled = editable,
                                description = "Sync $label",
                                tag = ClaudeAccountsTags.syncCategory(key),
                                onChange = { v ->
                                    val k = config.categories
                                    c.saveSync(
                                        config.copy(
                                            categories = when (key) {
                                                "plugins" -> k.copy(plugins = v)
                                                "skills" -> k.copy(skills = v)
                                                "mcp" -> k.copy(mcp = v)
                                                else -> k.copy(hooks = v)
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                },
            )
        }
        if (config.mode != ClaudeSyncMode.None) {
            val capable = accounts.filter { it.syncEligible }
            val titles = accounts.associate { it.id to ClaudeAccountsPresentation.card(it, null).title }
            val alike = LookAlike.collisions(accounts.map { titles.getValue(it.id) })
            val label: (ClaudeAccount) -> String = { a ->
                val i = accounts.indexOf(a)
                if (i in alike) "${titles.getValue(a.id)} · ${LabelText.visibleValue(a.id)}" else titles.getValue(a.id)
            }
            val primaryMissing = config.primaryAccountId == null
            SettingsRow(
                narrow = narrow,
                text = { m -> SettingsRowText("Primary account", AnnotatedString("Its plugins/skills/hooks/MCP servers are what the others receive"), m) },
                control = { m ->
                    TetherSelect(
                        options = listOf(TetherSelectOption("", if (primaryMissing) "Choose a primary account…" else "None", disabled = true)) +
                            capable.map { TetherSelectOption(it.id, label(it)) },
                        selectedValue = config.primaryAccountId?.takeIf { p -> capable.any { it.id == p } } ?: "",
                        onSelect = { o -> if (o.value.isNotEmpty()) c.saveSync(config.copy(primaryAccountId = o.value)) },
                        enabled = editable,
                        placeholder = sync.rows.lastOrNull { it.title == "Primary account" }?.value.orEmpty(),
                        contentDescription = "Primary account for sync: " + sync.rows.lastOrNull { it.title == "Primary account" }?.value.orEmpty(),
                        modifier = (if (narrow) m.fillMaxWidth() else m).testTag(ClaudeAccountsTags.SyncPrimary),
                    )
                },
            )
        }
        sync.warning?.let {
            RowRule()
            Text(it, color = t.warning, style = settingsText(type.ui, 12f, 400, lineHeight = 1.6f), modifier = Modifier.padding(vertical = 14.dp))
        }
        SettingsRow(
            narrow = narrow,
            text = { m ->
                val summary = if (c.busy == AccountsAction.SyncSave) ClaudeAccountsCopy.SAVING else sync.summary
                SettingsRowText("Sync status", AnnotatedString(summary), m.semantics { liveRegion = LiveRegionMode.Polite })
            },
            control = { m ->
                val running = c.busy == AccountsAction.SyncRun
                TetherKey(
                    onClick = { c.runSync() },
                    enabled = sync.canRun && c.canChange,
                    classes = KeyClasses.ButtonSecondary,
                    label = if (running) ClaudeAccountsCopy.SYNCING else ClaudeAccountsCopy.SYNC_NOW,
                    icon = if (running) TetherIcons.Loader else TetherIcons.RefreshCw,
                    iconSize = 14.dp,
                    modifier = m.testTag(ClaudeAccountsTags.SyncNow),
                )
            },
        )
        c.syncLine?.let { NoteLine(it, ClaudeAccountsTags.SyncLine) }
    }
}

/**
 * ta-7rh: Remove and Log out, asked first, in the web's confirm chrome (Devices' pattern). The
 * confirm key is armed after a beat for the confirmation it SHOWS (ta-q9l): one replaced while open
 * disarms it and the beat runs again; and what is sent is the confirmation shown
 * ([ClaudeAccountsController.confirmed]).
 */
@Composable
internal fun ClaudeAccountsConfirmDialog(confirm: AccountsConfirm, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val (title, body, action) = when (confirm) {
        is AccountsConfirm.Remove -> Triple(
            ClaudeAccountsCopy.REMOVE_TITLE,
            listOf(
                ClaudeAccountsCopy.removeBody(confirm.title),
                when {
                    confirm.hostDefault -> ClaudeAccountsCopy.REMOVE_HOST_DEFAULT
                    confirm.deleteCredentials -> ClaudeAccountsCopy.REMOVE_ALSO_LOGIN
                    else -> ClaudeAccountsCopy.REMOVE_KEEPS_LOGIN
                },
            ),
            ClaudeAccountsCopy.REMOVE_CONFIRM,
        )
        is AccountsConfirm.Logout -> Triple(ClaudeAccountsCopy.LOGOUT_TITLE, listOf(ClaudeAccountsCopy.logoutBody(confirm.title)), ClaudeAccountsCopy.LOGOUT_CONFIRM)
    }
    TetherDialog(
        onDismiss = onCancel,
        title = title,
        footer = {
            TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel", modifier = Modifier.testTag(ClaudeAccountsTags.ConfirmCancel))
            ArmedConfirmKey(action, ClaudeAccountsTags.ConfirmGo, onConfirm, classes = KeyClasses.ButtonDanger, shown = confirm)
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(ClaudeAccountsTags.ConfirmSheet), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            body.forEach { TetherDialogText(it) }
        }
    }
}
