package com.tether.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.ConnectionState
import com.tether.app.client.LabelText
import com.tether.app.client.NewSessionGuard
import com.tether.app.client.NewSessionResult
import com.tether.app.client.NewSessionRow
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography

// ta-895: the New session picker (interim until T8.1's draft composer). It lists what the web's
// draft composer offers: the `providers-snapshot` catalog (model-browser.tsx ModelBrowser's "all"
// view, `entries.map(ProviderRow)`), so every Claude account and every custom or ACP profile is its
// own row, in the server's order (lib/provider-catalog.mjs: profiles first, then the default rows).
// A tap creates the session on that row (no model is pinned: the engine's default, as the web's
// create does until the operator picks one). The model browser itself is ta-2uq.

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/** use-draft-composer.ts readiness, for a row still loading (nothing is sent). */
internal const val NEW_SESSION_LOADING_COPY = "Models are still loading."

/** The live catalog no longer offers the row as drawn (nothing was created). */
internal const val NEW_SESSION_NOT_OFFERED_COPY = "This server no longer offers that choice. Nothing was created; pick again from the updated list."

/** The picker was drawn for another server than the one now connected (nothing was sent). */
internal const val NEW_SESSION_NOT_LIVE_COPY = "The server changed. Nothing was created; pick again."

/** No live link (the client also raises its reconnecting toast). */
internal const val NEW_SESSION_NOT_CONNECTED_COPY = "The secure link is reconnecting. The session was not created."

/** Shown while this connection's catalog is not in yet (the base providers stand in). */
internal const val NEW_SESSION_CATALOG_PENDING_COPY = "Loading accounts and profiles…"

internal const val NEW_SESSION_ROW_TAG = "new-session-row-"
internal const val NEW_SESSION_NOTICE_TAG = "new-session-notice"
internal const val NEW_SESSION_PENDING_TAG = "new-session-pending"

/**
 * ta-895: "New session", wired to the client. Opening it asks for a fresh catalog (and again when
 * the link comes back while it is open), and it draws the profile rows only from the catalog the
 * current socket delivered ([com.tether.app.client.TetherClient.providerCatalogLive]); until then the
 * base providers stand in as their default rows. A tap goes through
 * [TetherViewModel.createNewSession], which the client re-checks under its lock; the dialog closes
 * only when the create went out, and otherwise says why and stays open.
 */
@Composable
fun NewSessionDialog(vm: TetherViewModel, onDismiss: () -> Unit) {
    val client = vm.client
    val connection by client.connection.collectAsStateWithLifecycle()
    val catalog by client.providerCatalog.collectAsStateWithLifecycle()
    val live by client.providerCatalogLive.collectAsStateWithLifecycle()
    val providers by client.providers.collectAsStateWithLifecycle()
    val origin by client.consentOrigin.collectAsStateWithLifecycle()
    val connected = connection == ConnectionState.Connected
    LaunchedEffect(connected) { if (connected) client.requestProviderCatalog() }
    var notice by remember { mutableStateOf<String?>(null) }
    val rows = NewSessionGuard.rows(if (live) catalog else null, providers)
    TetherDialog(onDismiss = onDismiss, title = "New session") {
        NewSessionPickerBody(rows = rows, providers = providers, catalogPending = !live, notice = notice) { row ->
            if (row.status == "loading") {
                notice = NEW_SESSION_LOADING_COPY
                return@NewSessionPickerBody
            }
            when (vm.createNewSession(row.choice, origin)) {
                NewSessionResult.Sent -> onDismiss()
                NewSessionResult.NotOffered -> {
                    notice = NEW_SESSION_NOT_OFFERED_COPY
                    client.requestProviderCatalog()
                }
                NewSessionResult.NotLive -> notice = NEW_SESSION_NOT_LIVE_COPY
                NewSessionResult.NotConnected -> notice = NEW_SESSION_NOT_CONNECTED_COPY
            }
        }
    }
}

/**
 * The picker's body (stateless): a pending line while the catalog is not in, then one row per
 * [rows] entry as model-browser.tsx ProviderRow draws it — the engine's mark (for a profile row the
 * engine it `extends`: the web's badge), the label (`entry.label ?? entry.provider`), the trailing
 * state in words ("N models", "Loading…", "Unavailable", "Error") and a chevron; an unavailable row
 * is disabled. The app adds one line the web does not draw: a profile row's id, so two accounts
 * labelled alike (or a profile labelled like the default row) never look the same; an error row also
 * shows its reason. Every server string is drawn by the text rules (labels [LabelText], ids
 * [codeLabel]).
 */
@Composable
fun ColumnScope.NewSessionPickerBody(
    rows: List<NewSessionRow>,
    providers: List<ProviderInfo>,
    catalogPending: Boolean,
    notice: String?,
    onPick: (NewSessionRow) -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    if (catalogPending) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = t.css.spaceSm).semantics { testTag = NEW_SESSION_PENDING_TAG },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) {
            Icon(TetherIcons.Loader, contentDescription = null, tint = t.faint, modifier = Modifier.size(13.dp))
            Text(NEW_SESSION_CATALOG_PENDING_COPY, style = type.body.copy(fontSize = rem(0.78f)), color = t.muted)
        }
    }
    if (rows.isEmpty()) {
        // model-browser.tsx: `entries.length === 0` → "No models available".
        Text(
            "No providers available.",
            style = type.body.copy(fontSize = rem(0.78f)),
            color = t.muted,
            modifier = Modifier.fillMaxWidth().padding(vertical = t.css.spaceMd),
        )
    }
    rows.forEachIndexed { index, row ->
        NewSessionRowView(row, glyphFor(providers, row.choice.provider), last = index == rows.lastIndex, onPick = onPick)
    }
    if (notice != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = t.css.spaceSm)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    testTag = NEW_SESSION_NOTICE_TAG
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) {
            Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.size(14.dp))
            Text(notice, style = type.body.copy(fontSize = rem(0.78f)), color = t.ink)
        }
    }
}

/** model-browser.tsx providerGlyph: the base provider's glyph, else the id's first letter. */
private fun glyphFor(providers: List<ProviderInfo>, provider: String): String? =
    providers.firstOrNull { it.id == provider }?.glyph?.let { LabelText.label(it) }?.takeIf { it.isNotEmpty() }

/**
 * The row's name as drawn: its label by the label rule; a label of nothing visible is spelled out
 * ([LabelText.visibleValue]), and a row without one shows its profile id or engine id, so a row is
 * never drawn as nothing or as another row's name.
 */
internal fun newSessionRowLabel(row: NewSessionRow): String {
    LabelText.label(row.label).takeIf { it.isNotEmpty() }?.let { return it }
    if (!row.label.isNullOrEmpty()) return LabelText.visibleValue(row.label)
    return LabelText.visibleValue(row.choice.profileId ?: row.choice.provider)
}

/** model-browser.tsx ProviderRow's trailing state, in words (never colour alone). */
internal fun newSessionRowState(row: NewSessionRow): String = when (row.status) {
    "ready" -> row.entry?.let { e -> "${e.models.size} ${if (e.models.size == 1) "model" else "models"}" } ?: ""
    "loading" -> "Loading…"
    "unavailable" -> "Unavailable"
    else -> "Error"
}

/** An error row's reason (model-browser.tsx ProviderView: `entry.error ?? "Failed to load models."`). */
internal fun newSessionRowError(row: NewSessionRow): String? {
    if (row.status == "ready" || row.status == "loading" || row.status == "unavailable") return null
    return LabelText.error(row.entry?.error).ifEmpty { "Failed to load models." }
}

@Composable
private fun NewSessionRowView(row: NewSessionRow, glyph: String?, last: Boolean, onPick: (NewSessionRow) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val name = newSessionRowLabel(row)
    val state = newSessionRowState(row)
    val error = newSessionRowError(row)
    val profileId = row.choice.profileId?.takeIf { it.isNotEmpty() }
    val enabled = row.status != "unavailable"
    val description = listOfNotNull(
        name,
        profileId?.let { "profile ${LabelText.visibleValue(it)}" },
        state.takeIf { it.isNotEmpty() },
        error,
    ).joinToString(", ")
    val clickLabel = "Start a new session on $name"
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = clickLabel) { onPick(row) }
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = description
                    testTag = NEW_SESSION_ROW_TAG + row.choice.key
                    if (enabled) onClick(clickLabel) { onPick(row); true } else disabled()
                }
                .heightIn(min = 44.dp)
                .padding(horizontal = t.css.spaceXs, vertical = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            CatalogGlyph(row.choice.provider, glyph)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.6.dp)) {
                Text(name, style = type.body.copy(fontSize = rem(0.82f)), color = t.white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (profileId != null) {
                    // ta-28i: an id is drawn by the one-line rule (hidden code points as tokens, LTR).
                    Text(codeLabel(profileId), style = type.body.copy(fontSize = rem(0.7f)), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (error != null) {
                    Text(error, style = type.body.copy(fontSize = rem(0.7f)), color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                when (row.status) {
                    "ready" -> if (state.isNotEmpty()) Text(state, style = type.body.copy(fontSize = rem(0.7f)), color = t.faint)
                    "loading" -> StateLabel(TetherIcons.Loader, state)
                    else -> StateLabel(TetherIcons.TriangleAlert, state)
                }
                Icon(TetherIcons.ChevronRight, contentDescription = null, tint = t.faint, modifier = Modifier.size(13.dp))
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/** `.model-browser-state`: the icon in `--warning` (the spinner `--faint`), the words `--faint`. */
@Composable
private fun StateLabel(icon: ImageVector, text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
        Icon(icon, contentDescription = null, tint = if (icon == TetherIcons.Loader) t.faint else t.warning, modifier = Modifier.size(13.dp))
        Text(text, style = type.body.copy(fontSize = rem(0.7f)), color = t.faint)
    }
}

/** `.model-browser-row .provider-glyph`: a 1.5rem glyph circle holding the engine's mark. */
@Composable
private fun CatalogGlyph(provider: String, glyph: String?) {
    val t = LocalTetherTokens.current
    Box(
        Modifier.size(24.dp).background(t.keyFace, CircleShape).border(1.dp, t.lineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        ProviderLogo(provider, fallback = glyph, color = t.ink, markSize = 14.dp, letterSize = rem(0.62f))
    }
}

/** For the goldens: [NewSessionPickerBody] without the wiring. */
@Composable
internal fun NewSessionPickerPreview(rows: List<NewSessionRow>, providers: List<ProviderInfo>, catalogPending: Boolean, notice: String?) {
    TetherDialog(onDismiss = {}, title = "New session") {
        NewSessionPickerBody(rows, providers, catalogPending, notice) {}
    }
}
