package com.tether.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.client.LabelText
import com.tether.app.client.TextCut
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.appendStyled
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tags of the Claude accounts section. */
object ClaudeAccountsTags {
    const val Section = "claude-accounts"
    const val OwnerOnly = "claude-accounts-owner-only"
    const val Loading = "claude-accounts-loading"
    const val Notice = "claude-accounts-notice"
    const val Retry = "claude-accounts-retry"
    const val Add = "claude-accounts-add"
    const val Sync = "claude-accounts-sync"
    const val SyncNow = "claude-accounts-sync-now"
    fun card(id: String) = "claude-account:$id"
    fun plan(id: String) = "claude-account-plan:$id"
    fun organization(id: String) = "claude-account-org:$id"
    fun status(id: String) = "claude-account-status:$id"
    fun id(id: String) = "claude-account-id:$id"
    fun path(id: String) = "claude-account-path:$id"
    fun check(id: String) = "claude-account-check:$id"
    fun rename(id: String) = "claude-account-rename:$id"
    fun login(id: String) = "claude-account-login:$id"
    fun logout(id: String) = "claude-account-logout:$id"
    fun remove(id: String) = "claude-account-remove:$id"
}

/**
 * ta-9q2: settings-dialog.tsx `ClaudeAccountsSection` (887c222 :1380-1867), read only, wired to
 * [binding]:
 * - read when the Engines tab opens (this is composed only then) and when the server changes;
 *   Retry reads again; one plan re-read [ClaudeAccountsModel.PLAN_RETRY_MS] after a first paint
 *   from the offline snapshot, once per opening (the web's `planRetriedRef`);
 * - Check reads one account's status (`GET …/<id>/status`, device-readable), as the web's Check;
 * - the sync state is read once two accounts are listed (the web mounts its sync section then);
 * - every answer is bound to [ClaudeAccountsBinding.origin]: another server's answer is dropped,
 *   and changing server cancels what is in flight and starts from nothing.
 * The owner-grade writes are drawn disabled with [ClaudeAccountsPresentation.OWNER_ONLY] and have no
 * code path to the network.
 */
@Composable
internal fun ClaudeAccountsHost(binding: ClaudeAccountsBinding, narrow: Boolean) {
    val timeOf = binding.timeOf
    val current = binding.origin
    val source = binding.source
    // A seed counts only for the server it was built for.
    val seed = binding.initial?.takeIf { current != null && it.origin == current }
    var state by remember(source, current) {
        mutableStateOf(seed ?: if (current == null) ClaudeAccountsModel.signedOut() else ClaudeAccountsState())
    }
    var reload by remember(source, current) { mutableIntStateOf(0) }
    var planRetried by remember(source, current) { mutableStateOf(false) }

    // Status checks belong to the server they were asked of: a new server cancels them.
    val scope = rememberCoroutineScope()
    val checks = remember(source, current) { SupervisorJob() }
    DisposableEffect(checks) { onDispose { checks.cancel() } }

    LaunchedEffect(source, current, reload) {
        if (current == null || (seed != null && reload == 0)) return@LaunchedEffect
        state = ClaudeAccountsModel.foldList(state, source.list(), current)
        if (!planRetried && state.listFault == null && ClaudeAccountsModel.wantsPlanRetry(state.accounts)) {
            planRetried = true
            delay(ClaudeAccountsModel.PLAN_RETRY_MS)
            state = ClaudeAccountsModel.foldList(state, source.list(), current)
        }
    }
    val wantsSync = ClaudeAccountsModel.wantsSync(state)
    LaunchedEffect(source, current, wantsSync) {
        if (current == null || seed != null || !wantsSync || state.sync != null) return@LaunchedEffect
        state = ClaudeAccountsModel.foldSync(state, source.sync(), current)
    }

    ClaudeAccountsSection(
        view = ClaudeAccountsPresentation.view(state, timeOf),
        narrow = narrow,
        onRetry = { reload++ },
        onCheck = onCheck@{ id ->
            if (current == null) return@onCheck
            // r2: asked only when the state moved to Checking: a second tap in the same frame (or
            // any tap while one is in flight, or for an id that may not be asked) sends nothing.
            val next = ClaudeAccountsModel.checking(state, id)
            if (next === state) return@onCheck
            state = next
            scope.launch(checks) { state = ClaudeAccountsModel.foldStatus(state, id, source.status(id), current) }
        },
    )
}

/** `new Date(ranAt).toLocaleTimeString()`. */
internal fun localTime(at: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(at))

/** The section, stateless over [view]. */
@Composable
internal fun ClaudeAccountsSection(
    view: ClaudeAccountsPresentation.View,
    narrow: Boolean,
    onRetry: () -> Unit,
    onCheck: (String) -> Unit,
) {
    SettingsSection(
        ClaudeAccountsPresentation.TITLE,
        AnnotatedString(ClaudeAccountsPresentation.INTRO),
        narrow,
        modifier = Modifier.testTag(ClaudeAccountsTags.Section),
    ) {
        OwnerGradeNote(ClaudeAccountsPresentation.OWNER_ONLY, Modifier.testTag(ClaudeAccountsTags.OwnerOnly).padding(bottom = 16.dp))
        when {
            view.notice != null -> ListNotice(view.notice, narrow, onRetry)
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
                view.cards.forEach { AccountCard(it, narrow, onCheck) }
            }
        }
        TetherKey(
            onClick = {},
            enabled = false,
            classes = KeyClasses.ButtonSecondary,
            label = "Add Claude account",
            icon = TetherIcons.Plus,
            iconSize = 14.dp,
            modifier = Modifier.padding(top = if (view.cards.isEmpty()) 0.dp else 20.dp).testTag(ClaudeAccountsTags.Add),
        )
        view.sync?.let { SyncRows(it, narrow) }
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
 * Sign-in row (the CLAUDE_CONFIG_DIR, by the path rule) and the Remove row. On a phone the keys drop
 * under the title so the title keeps the width.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCard(card: ClaudeAccountsPresentation.Card, narrow: Boolean, onCheck: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier
            .testTag(ClaudeAccountsTags.card(card.id))
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(12.dp), t.graphite, CssBorder(1.dp, t.line), emptyList())
            .padding(if (narrow) 16.dp else 20.dp),
    ) {
        val keys: @Composable () -> Unit = {
            if (card.canRename) {
                TetherKey(
                    onClick = {},
                    enabled = false,
                    classes = KeyClasses.ButtonSecondary,
                    label = "Rename",
                    icon = TetherIcons.Pencil,
                    iconSize = 14.dp,
                    contentDescription = "Rename ${card.title}",
                    modifier = Modifier.testTag(ClaudeAccountsTags.rename(card.id)),
                )
            }
            TetherKey(
                onClick = { onCheck(card.id) },
                enabled = card.canCheck,
                classes = KeyClasses.ButtonSecondary,
                label = "Check",
                icon = if (card.checking) TetherIcons.Loader else TetherIcons.RefreshCw,
                iconSize = 14.dp,
                contentDescription = "Check ${card.title}",
                modifier = Modifier.testTag(ClaudeAccountsTags.check(card.id)),
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
                        Tag(it, Modifier.testTag(ClaudeAccountsTags.plan(card.id)).align(Alignment.CenterVertically).semantics { contentDescription = "Plan: $it" })
                    }
                    if (card.preExisting) {
                        Tag(ClaudeAccountsPresentation.PRE_EXISTING, Modifier.align(Alignment.CenterVertically).semantics { contentDescription = ClaudeAccountsPresentation.PRE_EXISTING_TIP })
                    }
                }
                card.idLine?.let { id ->
                    // r2: this title could pass for another's: the profile id tells them apart.
                    Text(
                        accountIdText(id),
                        style = settingsText(type.mono, 11.5f, 400, lineHeight = 1.5f),
                        color = t.faint,
                        modifier = Modifier
                            .testTag(ClaudeAccountsTags.id(card.id))
                            .semantics { contentDescription = "profile ${LabelText.visibleValue(id)}" },
                    )
                }
                Text(
                    card.status,
                    color = t.muted,
                    style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f),
                    modifier = Modifier.testTag(ClaudeAccountsTags.status(card.id)).semantics { liveRegion = LiveRegionMode.Polite },
                )
                card.organization?.let {
                    Text(
                        it,
                        color = t.muted,
                        style = settingsText(type.ui, 12f, 400, lineHeight = 1.5f),
                        modifier = Modifier.testTag(ClaudeAccountsTags.organization(card.id)),
                    )
                }
            }
            if (!narrow) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { keys() }
        }
        if (narrow) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { keys() }
        // `.engine-card-body`: a rule, then the rows (the first without its own rule).
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(t.line))
        CardRow(
            narrow = narrow,
            title = "Sign-in",
            caption = card.configDir?.let { codeLabel(it) } ?: AnnotatedString(ClaudeAccountsPresentation.NO_HOME),
            captionTag = ClaudeAccountsTags.path(card.id),
            first = true,
        ) {
            OwnerKey("Log in", TetherIcons.LogIn, "Log in ${card.title}", ClaudeAccountsTags.login(card.id))
            OwnerKey("Log out", TetherIcons.LogOut, "Log out ${card.title}", ClaudeAccountsTags.logout(card.id))
        }
        CardRow(narrow = narrow, title = "Remove account", caption = AnnotatedString("Deletes the profile entry from Tether")) {
            OwnerKey("Remove", TetherIcons.Trash2, "Remove ${card.title}", ClaudeAccountsTags.remove(card.id))
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

/** A disabled owner-grade key: drawn so the reader sees the web's control, never wired to anything. */
@Composable
private fun OwnerKey(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tag: String) {
    TetherKey(
        onClick = {},
        enabled = false,
        classes = KeyClasses.ButtonSecondary,
        label = label,
        icon = icon,
        iconSize = 14.dp,
        contentDescription = description,
        modifier = Modifier.testTag(tag),
    )
}

/** A `.settings-row` inside a card (`.engine-card-body .settings-row:first-child { border-top: 0 }`). */
@Composable
private fun CardRow(
    narrow: Boolean,
    title: String,
    caption: AnnotatedString,
    captionTag: String? = null,
    first: Boolean = false,
    keys: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (!first) RowRule()
        val text: @Composable (Modifier) -> Unit = { m ->
            SettingsRowText(title, caption, if (captionTag != null) m.testTag(captionTag) else m)
        }
        if (narrow) {
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                text(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { keys() }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                text(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { keys() }
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

/** ClaudeAccountSyncSection (settings-dialog.tsx:1241-1376), read only: each setting's value, the status, Sync now disabled. */
@Composable
private fun SyncRows(sync: ClaudeAccountsPresentation.SyncView, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.testTag(ClaudeAccountsTags.Sync).padding(top = 20.dp)) {
        sync.rows.forEach { row ->
            SettingsRow(
                narrow = narrow,
                modifier = Modifier.semantics(mergeDescendants = true) { },
                text = { m -> SettingsRowText(row.title, AnnotatedString(row.caption), m) },
                control = { m ->
                    Text(row.value, color = t.ink, style = settingsText(type.ui, 13f, 600, lineHeight = 1.5f), modifier = m)
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
                SettingsRowText("Sync status", AnnotatedString(sync.summary), m.semantics { liveRegion = LiveRegionMode.Polite })
            },
            control = { m ->
                TetherKey(
                    onClick = {},
                    enabled = sync.canRun,
                    classes = KeyClasses.ButtonSecondary,
                    label = "Sync now",
                    icon = TetherIcons.RefreshCw,
                    iconSize = 14.dp,
                    modifier = m.testTag(ClaudeAccountsTags.SyncNow),
                )
            },
        )
    }
}
