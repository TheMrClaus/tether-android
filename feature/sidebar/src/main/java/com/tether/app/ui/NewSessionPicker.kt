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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.client.DRAFT_NOT_OFFERED_COPY
import com.tether.app.client.LabelText
import com.tether.app.client.NewSessionRow
import com.tether.app.client.TextCut
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.appendStyled
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography

// ta-895: the New session picker's provider and profile rows. It lists what the web's
// draft composer offers: the `providers-snapshot` catalog (model-browser.tsx ModelBrowser's "all"
// view, `entries.map(ProviderRow)`), so every Claude account and every custom or ACP profile is its
// own row, in the server's order (lib/provider-catalog.mjs: profiles first, then the default rows).
// A tap creates the session on that row (no model is pinned: the engine's default, as the web's
// create does until the operator picks one). The model browser itself is ta-2uq.
// ta-abm (T8.1 slice 2): the interim dialog that created on a tap is gone. These rows are now the
// draft composer sheet's provider stage (feature:shell DraftComposerSheet): a tap picks the row
// (SET_PROVIDER_FROM_USER) and the sheet's Send creates the session with its first message. The
// model browser (ta-2uq, slice 3) replaces this stage with the web's ModelSelector chip.

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/** The live catalog no longer offers the row as drawn (nothing was created). */
internal const val NEW_SESSION_NOT_OFFERED_COPY = DRAFT_NOT_OFFERED_COPY

/** Shown while this connection's catalog is not in yet (the base providers stand in). */
internal const val NEW_SESSION_CATALOG_PENDING_COPY = "Loading accounts and profiles…"

/** ta-8cv: the create went out; the dialog waits for the server's answer to it. */
internal const val NEW_SESSION_CREATING_COPY = "Starting the session…"

const val NEW_SESSION_ROW_TAG = "new-session-row-"
internal const val NEW_SESSION_NOTICE_TAG = "new-session-notice"
const val NEW_SESSION_PENDING_TAG = "new-session-pending"
internal const val NEW_SESSION_CREATING_TAG = "new-session-creating"

/**
 * The picker's body (stateless): a pending line while the catalog is not in, then one row per
 * [rows] entry as model-browser.tsx ProviderRow draws it — the engine's mark (for a profile row the
 * engine it `extends`: the web's badge), the label (`entry.label ?? entry.provider`), the trailing
 * state in words ("N models", "Loading…", "Unavailable", "Error"; r2: "Not offered" for a row the
 * client refuses) and a chevron; a row that cannot create is disabled ([newSessionRowEnabled]). The
 * app adds what the web does not draw: a profile row's id, whole (it wraps), so two accounts
 * labelled alike (or a profile labelled like the default row) never look the same, and a short tag
 * after a label another row shares ([newSessionRowTags]); an error row also shows its reason. Every
 * server string is drawn by the text rules (labels [LabelText], ids [SafeText.Rule.Line]).
 */
@Composable
fun ColumnScope.NewSessionPickerBody(
    rows: List<NewSessionRow>,
    providers: List<ProviderInfo>,
    catalogPending: Boolean,
    notice: String?,
    creating: Boolean = false,
    /** ta-abm: the row the draft has picked (drawn checked and announced selected); null = none. */
    selectedKey: String? = null,
    /** ta-abm: what a tap on a row does, in words ("Choose …" in the sheet). */
    pickLabel: (name: String) -> String = { "Start a new session on $it" },
    onPick: (NewSessionRow) -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    if (creating) {
        // ta-8cv: the pending line's look; every row is locked until the create's own reply.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = t.css.spaceSm)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    testTag = NEW_SESSION_CREATING_TAG
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) {
            Icon(TetherIcons.Loader, contentDescription = null, tint = t.faint, modifier = Modifier.size(13.dp))
            Text(NEW_SESSION_CREATING_COPY, style = type.body.copy(fontSize = rem(0.78f)), color = t.muted)
        }
    }
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
    val tags = newSessionRowTags(rows)
    rows.forEachIndexed { index, row ->
        NewSessionRowView(
            row, tags[index], glyphFor(providers, row.choice.provider), last = index == rows.lastIndex, locked = creating,
            selected = selectedKey != null && row.choice.key == selectedKey, pickLabel = pickLabel, onPick = onPick,
        )
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

/**
 * r2 (F2): the row takes a tap. A row that can create does; so does a loading one (the tap says
 * "Models are still loading.", as the web's readiness does, and sends nothing). Every other row
 * (unavailable, or one the client would refuse: a duplicated key, a malformed row, an unknown
 * status) is drawn disabled, so it never looks like it would start a session.
 */
internal fun newSessionRowEnabled(row: NewSessionRow): Boolean = row.creatable || row.status == "loading"

/** model-browser.tsx ProviderRow's trailing state, in words (never colour alone). */
internal fun newSessionRowState(row: NewSessionRow): String = when {
    row.status == "loading" -> "Loading…"
    row.status == "unavailable" -> "Unavailable"
    // r2 (F2): a row the client refuses whatever its status says (duplicated, malformed, unknown).
    !row.creatable -> "Not offered"
    row.status == "ready" -> row.entry?.let { e -> "${e.models.size} ${if (e.models.size == 1) "model" else "models"}" } ?: ""
    else -> "Error"
}

/** An error row's reason (model-browser.tsx ProviderView: `entry.error ?? "Failed to load models."`). */
internal fun newSessionRowError(row: NewSessionRow): String? {
    if (row.status != "error") return null
    return LabelText.error(row.entry?.error).ifEmpty { "Failed to load models." }
}

/**
 * r2 (F1): rows whose drawn label is the same ("Claude Code (work)" twice: the server names an
 * account after its nickname and only its id gets the "-2") each carry a short stable tag of their
 * key, `#` + 6 hex of its SHA-256 (the [LabelText.visibleValue] tag), drawn after the label and
 * never cut. Null for a row whose label is its own.
 */
internal fun newSessionRowTags(rows: List<NewSessionRow>): List<String?> {
    val labels = rows.map(::newSessionRowLabel)
    val counts = labels.groupingBy { it }.eachCount()
    return rows.mapIndexed { i, row -> if ((counts[labels[i]] ?: 0) > 1) "#" + shortHash(row.choice.key) else null }
}

private fun shortHash(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(6)

/** r2 (F1): an id longer than this is cut in the MIDDLE, so its end (a "-2" suffix) always shows. */
internal const val PROFILE_ID_SHOWN = 96

/**
 * The raw parts of [id] to draw: the whole id, or (longer than [PROFILE_ID_SHOWN]) its head and its
 * tail, each cut at a character-cluster boundary ([TextCut]), drawn with "…" between them.
 */
internal fun profileIdParts(id: String): List<String> {
    if (id.length <= PROFILE_ID_SHOWN) return listOf(id)
    val half = (PROFILE_ID_SHOWN - 1) / 2
    val headEnd = TextCut.boundaryAtOrBefore(id, half)
    val tailStart = TextCut.boundaryAtOrBefore(id, id.length - half, floor = headEnd)
    return listOf(id.substring(0, headEnd), id.substring(tailStart))
}

/**
 * r2 (F1): a profile id as drawn: the one-line rule ([SafeText.Rule.Line]: hidden code points and
 * line breaks as tokens) in an LTR paragraph, a break opportunity between any two characters, and
 * never cut at its end: it wraps, and only an id longer than [PROFILE_ID_SHOWN] is cut, in the middle.
 */
@Composable
private fun profileIdText(id: String): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(id, t) {
        val style = tokenStyle(t)
        AnnotatedString.Builder(id.length * 2).apply {
            withStyle(ParagraphStyle(textDirection = codeDirection)) {
                profileIdParts(id).forEachIndexed { i, part ->
                    if (i > 0) append("…")
                    appendStyled(SafeText.breakAnywhere(SafeText.encode(part, SafeText.Rule.Line)), style)
                }
            }
        }.toAnnotatedString()
    }
}

@Composable
private fun NewSessionRowView(
    row: NewSessionRow,
    tag: String?,
    glyph: String?,
    last: Boolean,
    locked: Boolean,
    selected: Boolean,
    pickLabel: (String) -> String,
    onPick: (NewSessionRow) -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val name = newSessionRowLabel(row)
    val state = newSessionRowState(row)
    val error = newSessionRowError(row)
    val profileId = row.choice.profileId?.takeIf { it.isNotEmpty() }
    val enabled = newSessionRowEnabled(row) && !locked
    val description = listOfNotNull(
        name,
        tag?.let { "tag ${it.removePrefix("#")}" },
        profileId?.let { "profile ${LabelText.visibleValue(it)}" },
        state.takeIf { it.isNotEmpty() },
        error,
    ).joinToString(", ")
    val clickLabel = pickLabel(name)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = clickLabel) { onPick(row) }
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = description
                    testTag = NEW_SESSION_ROW_TAG + row.choice.key
                    if (selected) this.selected = true
                    if (enabled) onClick(clickLabel) { onPick(row); true } else disabled()
                }
                .heightIn(min = 44.dp)
                .padding(horizontal = t.css.spaceXs, vertical = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            CatalogGlyph(row.choice.provider, glyph)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                    Text(name, style = type.body.copy(fontSize = rem(0.82f)), color = t.white, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    // r2 (F1): the tag that tells two same-labelled rows apart is never cut.
                    if (tag != null) Text(tag, style = type.body.copy(fontSize = rem(0.7f), fontFamily = JetBrainsMono), color = t.faint, maxLines = 1, softWrap = false)
                }
                if (profileId != null) {
                    // ta-28i: an id is drawn by the one-line rule (hidden code points as tokens, LTR);
                    // r2 (F1): it wraps rather than losing its end.
                    Text(profileIdText(profileId), style = type.body.copy(fontSize = rem(0.7f)), color = t.faint)
                }
                if (error != null) {
                    Text(error, style = type.body.copy(fontSize = rem(0.7f)), color = t.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                when {
                    row.status == "loading" -> StateLabel(TetherIcons.Loader, state)
                    row.status == "ready" && row.creatable -> if (state.isNotEmpty()) Text(state, style = type.body.copy(fontSize = rem(0.7f)), color = t.faint)
                    // Error, Unavailable, and (r2) every "Not offered" row: the warning mark.
                    else -> StateLabel(TetherIcons.TriangleAlert, state)
                }
                // ta-abm: the draft's pick is checked (`.tether-select-option.is-selected`'s Check, in violet).
                if (selected) {
                    Icon(TetherIcons.Check, contentDescription = null, tint = t.violet, modifier = Modifier.size(14.dp))
                } else {
                    Icon(TetherIcons.ChevronRight, contentDescription = null, tint = t.faint, modifier = Modifier.size(13.dp))
                }
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

/**
 * For the goldens: [NewSessionPickerBody] without the wiring, in the dialog case ta-895 shot it in.
 * ta-abm: kept as the rows' own goldens (their rendering is unchanged); the sheet's goldens show
 * them in place.
 */
@Composable
internal fun NewSessionPickerPreview(rows: List<NewSessionRow>, providers: List<ProviderInfo>, catalogPending: Boolean, notice: String?) {
    TetherDialog(onDismiss = {}, title = "New session") {
        NewSessionPickerBody(rows, providers, catalogPending, notice) {}
    }
}
