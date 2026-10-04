package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.ControlResult
import com.tether.app.client.NoticeResult
import com.tether.app.client.SessionControl
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.components.maxWidthFraction
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherTypography
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/*
 * T6.6: the notice rows and the limit card (chat-view.tsx 1332-1398, 3434-3471, 3533-3652;
 * codex-rich-renderers.tsx 366-418; notice-dismiss-button.tsx). Styles: globals.css 6341-6396
 * (.chat-outcome, .chat-continuation, .chat-continuation-cancel, .notice-dismiss), 6168-6318 +
 * 8546-8553 (.chat-approval / .chat-rate-limit), codex-rich-renderers.module.css 340-401 (.notice).
 *
 * Operator discipline (T6.3 / T6.4 / T7.2): a row only calls [NoticeActions] from a tap on its X,
 * the limit card only from a tap on one of its keys; an X sends on every tap and the limit card's
 * keys rest for 4 s after a choice, as the web's do (the client's own checks under its lock); never
 * in answer to anything received, and shows why in words when it cannot act. Nothing is retried on
 * its own.
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/**
 * What the notices and the limit card of one session may do. [onDismiss] and [onRateLimit] are the
 * ONLY ways they reach the wire. [link] names the connection they were drawn on: a latch set on one
 * link never holds a key disabled on the next. ta-coik.23: nothing here locks on the link or the
 * copy's liveness. As on the web (notice-dismiss-button.tsx, chat-view.tsx 90fbb9f :1384-1394,
 * :3707-3714), the X and the limit card's keys stay live offline and catching up; the client sends on
 * an open socket and otherwise says the link is reconnecting. ta-coik.23 r2: nor on the session's
 * read-only or handed-off state: the web draws the card's keys and the cancel live there too
 * (chat-view.tsx :3693-3714); the server refuses a read-only session's choice with an `error`, shown.
 */
@Immutable
class NoticeActions(
    val sessionId: String?,
    val link: Any?,
    internal val onDismiss: (dismissKey: String) -> NoticeResult,
    internal val onRateLimit: (SessionControl.RateLimitResume) -> ControlResult,
    /** Says a refusal in words (the screen's error toast). */
    internal val onRefused: (String) -> Unit = {},
) {
    companion object {
        /** No session: nothing is sent. */
        val Unavailable = NoticeActions(
            sessionId = null,
            link = null,
            onDismiss = { NoticeResult.NotConnected },
            onRateLimit = { ControlResult.NotConnected },
        )
    }
}

/** The notices read their session's actions here. */
val LocalNoticeActions = compositionLocalOf { NoticeActions.Unavailable }

/**
 * ta-coik.23 r2: the client's NotLive now means only "drawn for another server"; the app's words for
 * that elsewhere (settings' NOT_SENT / NOT_SENT_OTHER).
 */
internal const val OTHER_SERVER_NOT_SENT = "Nothing was sent: the app is now signed in to another server."

/** The words for a dismissal that sent nothing (null: nothing to say — the notice is already gone, or the client said it). */
internal fun noticeRefusalCopy(result: NoticeResult): String? = when (result) {
    NoticeResult.Sent, NoticeResult.AlreadySent, NoticeResult.NotShown, NoticeResult.NotConnected -> null
    NoticeResult.NotLive -> OTHER_SERVER_NOT_SENT
    NoticeResult.Locked -> "This session can’t be changed from here."
}

/** The words for a limit choice that sent nothing. */
internal fun rateLimitRefusalCopy(result: ControlResult): String? = when (result) {
    ControlResult.Sent, ControlResult.NotConnected -> null
    ControlResult.NotLive -> OTHER_SERVER_NOT_SENT
    ControlResult.Locked -> "This session can’t be changed from here."
    ControlResult.NotOffered -> "That limit prompt is no longer active — nothing was sent."
}

/**
 * chat-view.tsx clockTime: `Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit",
 * timeZoneName: "short" })` — the locale's short time plus the zone's short name ("3:07 PM UTC").
 */
internal fun limitClockTime(epochMs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
    val formatter = DateTimeFormatter.ofPattern("$pattern z", locale)
    // A bare UTC offset prints its id ("Z"); Intl names it "UTC".
    val shown = if (zone == java.time.ZoneOffset.UTC) ZoneId.of("UTC") else zone
    return formatter.format(Instant.ofEpochMilli(epochMs).atZone(shown))
}

/**
 * `NoticeDismissButton` (`.chat-continuation-cancel.notice-dismiss`): the 13px X in a 44dp square
 * (the coarse-pointer hit area), `title` + `aria-label` = [label]. One tap sends one dismissal; as on
 * the web (notice-dismiss-button.tsx 90fbb9f :12-20, ta-coik.22) there is no latch: the X stays live
 * until the notice folds away, and another tap sends again.
 */
@Composable
internal fun NoticeDismissButton(dismissKey: String, label: String, modifier: Modifier = Modifier) {
    // T6.7 r2: never part of a text selection, wherever it is drawn.
    androidx.compose.foundation.text.selection.DisableSelection { NoticeDismissButtonBody(dismissKey, label, modifier) }
}

@Composable
private fun NoticeDismissButtonBody(dismissKey: String, label: String, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val actions = LocalNoticeActions.current
    // Scoped to the session too (r2): another session's notice with the same key never inherits this latch or arming.
    val identity = Triple(actions.sessionId, dismissKey, actions.link)
    // ta-coik.13: the first tap dismisses, as on the web (notice-dismiss-button.tsx 90fbb9f :12-20,
    // no arm delay, never disabled; ta-coik.23: not offline or catching up either); a press across a
    // change of notice is dropped ([StaleTapGuard]); no overlay taps.
    val tap = {
        val result = actions.onDismiss(dismissKey)
        if (result != NoticeResult.Sent && result != NoticeResult.AlreadySent) noticeRefusalCopy(result)?.let(actions.onRefused)
    }
    StaleTapGuard(identity) { guard ->
        Box(
            modifier
                .then(guard)
                .size(TetherDimens.touchTargetDp)
                .clickable(role = Role.Button, onClickLabel = label, onClick = tap)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    role = Role.Button
                }
                .testTag("notice-dismiss"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
        }
    }
}

/**
 * One `CodexNotices` row (`.notice` + `.notice_<level>`): the level's icon (CircleDot for info, the
 * triangle otherwise, named for TalkBack), the bold uppercase lead-in over the message, the X.
 * `role="alert"` for an error, `status` otherwise. The level also rides in words for TalkBack.
 */
@Composable
fun ProviderNoticeRow(view: ProviderNoticeView, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val edge = when (view.level) {
        "warning" -> t.attentionBorder
        "error" -> t.dangerEdge
        else -> t.line
    }
    val iconTint = when (view.level) {
        "warning" -> t.warning
        "error" -> t.danger
        else -> t.muted
    }
    val levelWord = when (view.level) {
        "info" -> "Info"
        "error" -> "Error"
        else -> "Warning"
    }
    NoticeBox(edge, modifier.testTag("provider-notice")) {
        Icon(
            if (view.level == "info") TetherIcons.CircleDot else TetherIcons.TriangleAlert,
            contentDescription = levelWord,
            tint = iconTint,
            modifier = Modifier.padding(top = 2.dp).size(14.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    liveRegion = if (view.level == "error") LiveRegionMode.Assertive else LiveRegionMode.Polite
                },
            verticalArrangement = Arrangement.spacedBy((0.12f * TetherTypography.SP_PER_REM).dp),
        ) {
            NoticeHeading(view.heading)
            if (view.message.isNotEmpty()) {
                Text(view.message, style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.78f), lineHeight = rem(0.78f) * 1.45f), color = t.muted)
            }
        }
        view.dismissKey?.let { NoticeDismissButton(it, "Dismiss notice", Modifier.padding(start = 0.dp)) }
    }
}

/** A Codex turn's "Context compacted" row (Package icon, the lead-in, the one line of copy, the X). */
@Composable
internal fun CompactionRow(view: CompactionView, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    NoticeBox(t.line, modifier.testTag("compaction-notice")) {
        Icon(TetherIcons.Package, contentDescription = null, tint = t.muted, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy((0.12f * TetherTypography.SP_PER_REM).dp),
        ) {
            NoticeHeading("Context compacted")
            Text(
                "Codex condensed earlier context to continue this turn.",
                style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.78f), lineHeight = rem(0.78f) * 1.45f),
                color = t.muted,
            )
        }
        view.dismissKey?.let { NoticeDismissButton(it, "Dismiss context-compacted notice") }
    }
}

/**
 * `.notice`: a row on `--mineral-deep`, 1px [edge], `--radius-sm`, padded `space-sm space-md`,
 * `--chat-card-width` (100% on a phone). The X row sits at the top right; its 44dp square is
 * pulled into the padding so the box keeps the web's height.
 */
@Composable
private fun NoticeBox(edge: androidx.compose.ui.graphics.Color, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier
                .maxWidthFraction(cardFraction())
                .fillMaxWidth()
                .cssSurface(shape, background = t.mineralDeep, border = CssBorder(1.dp, edge))
                .padding(start = t.css.spaceMd, end = t.css.spaceXs, top = t.css.spaceSm, bottom = t.css.spaceSm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            content = content,
        )
    }
}

/** `.notice strong`: ink 0.68rem, 0.04em tracking, uppercase. */
@Composable
private fun NoticeHeading(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text.uppercase(),
        style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.68f), fontWeight = FontWeight(700), letterSpacing = 0.04.em),
        color = t.ink,
        modifier = Modifier.semantics { contentDescription = text },
    )
}

/**
 * A session notice (`.chat-outcome.chat-outcome-outcome_unknown`, `role="status"`): the warning
 * triangle and the sentence in `--warning` on `--tint-xs`, the X at the end. The words carry the
 * state; the colour only reinforces it.
 */
@Composable
internal fun SessionNoticeRow(view: SessionNoticeView, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        modifier
            .background(t.tintXs, RoundedCornerShape(t.radiusSm))
            .padding(start = t.css.spaceSm, top = t.css.spaceXs, bottom = t.css.spaceXs, end = if (view.dismissKey != null) 0.dp else t.css.spaceSm)
            .testTag("session-notice"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.size(13.dp))
        Text(
            view.text,
            style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.78f)),
            color = t.warning,
            modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
        )
        view.dismissKey?.let { NoticeDismissButton(it, view.dismissLabel) }
    }
}

/**
 * The limit card (`RateLimitResumeCard`, `.chat-approval.chat-rate-limit`, `role="alertdialog"`):
 * "Limit hit", the reset time, why a schedule waits two minutes, and three keys — Schedule
 * auto-continue · <time>, Resume now, Dismiss. Each is bound to THIS prompt's `resetsAt`, acts on
 * the first tap as on the web (chat-view.tsx 90fbb9f :1384-1394, no arm delay; ta-coik.13), drops a
 * press across a change of prompt ([StaleTapGuard]) and is refused under an overlay. A choice the
 * client sent disables the keys while the card waits for the server's event to remove it; as on the
 * web (chat-view.tsx 90fbb9f :1361-1371, ta-coik.22), they re-enable after [RATE_LIMIT_RETRY_MS] so a
 * choice a half-open link swallowed can be made again (a new link re-enables them at once too).
 * ta-coik.23 r2: live on a handed-off or read-only session too, as on the web (the server answers).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RateLimitCard(view: RateLimitPromptView, modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val actions = LocalNoticeActions.current
    val shape = RoundedCornerShape(14.dp)
    // Scoped to the session too (r2): another session's prompt with the same resetsAt starts unsent.
    val identity = Triple(actions.sessionId, view.resetsAt, actions.link)
    var sent by remember(identity) { mutableStateOf<String?>(null) }
    var overlayBlocked by remember(identity) { mutableStateOf(false) }
    // chat-view.tsx 90fbb9f :1361-1368: "If a half-open socket swallows the fire-and-forget control
    // message, let the operator retry instead of leaving both choices disabled forever."
    LaunchedEffect(identity, sent) {
        if (sent != null) {
            delay(RATE_LIMIT_RETRY_MS)
            sent = null
        }
    }
    val armed = sent == null
    val resetClock = limitClockTime(view.resetsAt, locale, zone)
    val resumeClock = limitClockTime(view.resumeAt, locale, zone)

    fun choose(action: String) {
        if (sent != null || !armed) return
        sent = action
        val result = actions.onRateLimit(SessionControl.RateLimitResume(view.resetsAt, action))
        if (result != ControlResult.Sent) {
            sent = null
            rateLimitRefusalCopy(result)?.let(actions.onRefused)
        }
    }
    val blocked = { overlayBlocked = true }

    StaleTapGuard(identity) { guard ->
        Column(
            modifier
                .then(guard)
                .fillMaxWidth()
                .cssSurface(
                    shape,
                    background = t.attentionBg,
                    border = CssBorder(1.dp, t.attentionBorder),
                    shadows = emptyList(),
                )
                .padding(20.dp)
                .semantics { paneTitle = "Limit hit" }
                .testTag("rate-limit-card"),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                Icon(TetherIcons.Clock, contentDescription = null, tint = t.attentionInk, modifier = Modifier.size(15.dp))
                Text(
                    "Limit hit",
                    style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.98f), fontWeight = FontWeight(700), letterSpacing = (-0.01).em),
                    color = t.white,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(
                "Resets at $resetClock.",
                style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.85f)),
                color = t.ink,
                modifier = Modifier.padding(vertical = (0.85f * TetherTypography.SP_PER_REM).dp),
            )
            Text(
                buildAnnotatedString {
                    append("Schedule auto-continue to ask the agent to resume actual work at ")
                    withStyle(SpanStyle(color = t.ink)) { append(resumeClock) }
                    append(" — two minutes after the reset.")
                },
                style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.8f), lineHeight = rem(0.8f) * 1.5f),
                color = t.muted,
            )
            val status = when {
                overlayBlocked && sent == null -> OVERLAY_COPY.replace("answer", "choose")
                sent != null -> "Choice sent. Waiting for the server."
                else -> null
            }
            status?.let {
                Text(
                    it,
                    style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.8f), lineHeight = rem(0.8f) * 1.5f),
                    color = t.muted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("rate-limit-status"),
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = t.css.spaceXs),
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                val phone = currentLayoutClass() == TetherLayoutClass.Phone
                // globals.css:8546 (phone): each key takes the full row.
                val keyModifier = (if (phone) Modifier.fillMaxWidth() else Modifier).refuseObscuredTouches(blocked)
                TetherKey(
                    onClick = { choose("schedule") },
                    classes = KeyClasses.ButtonPrimary,
                    label = "Schedule auto-continue · $resumeClock",
                    icon = TetherIcons.Clock,
                    enabled = armed,
                    modifier = keyModifier.testTag("rate-limit-schedule"),
                )
                TetherKey(
                    onClick = { choose("resume-now") },
                    classes = KeyClasses.ButtonSecondary,
                    label = "Resume now",
                    icon = TetherIcons.Play,
                    enabled = armed,
                    modifier = keyModifier.testTag("rate-limit-resume-now"),
                )
                TetherKey(
                    onClick = { choose("dismiss") },
                    classes = KeyClasses.ButtonSecondary,
                    label = "Dismiss",
                    icon = TetherIcons.Ban,
                    enabled = armed,
                    modifier = keyModifier.testTag("rate-limit-dismiss"),
                )
            }
        }
    }
}

/** chat-view.tsx 90fbb9f :1367: how long a sent limit choice keeps the card's keys disabled. */
internal const val RATE_LIMIT_RETRY_MS = 4_000L



/**
 * The scheduled resume (`.chat-continuation`, `role="status"`): Clock, "Automatic resume scheduled
 * for <time>.", and the cancel X ("Cancel scheduled resume"), which sends `dismiss` for this
 * prompt's `resetsAt` — it cancels a turn the server would start, so it takes T7.2's guarded path.
 * ta-coik.23 r2: never locked, as on the web (chat-view.tsx 90fbb9f :3707-3714), read-only and
 * handed-off sessions included (the server answers a read-only one with an `error`, shown).
 */
@Composable
internal fun ScheduledResumeRow(view: RateLimitPromptView, modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val actions = LocalNoticeActions.current
    // Scoped to the session too (r2), like the card.
    val identity = Triple(actions.sessionId, view.resetsAt, actions.link)
    // ta-coik.13: the first tap cancels, as on the web (chat-view.tsx 90fbb9f :3707-3714, no arm
    // delay); a press across a change of prompt is dropped ([StaleTapGuard]); no overlay taps.
    // ta-coik.22: no "Cancelling…" latch, as on the web (chat-view.tsx 90fbb9f :3707-3714): each tap sends.
    val label = "Cancel scheduled resume"
    val tap = {
        val result = actions.onRateLimit(SessionControl.RateLimitResume(view.resetsAt, "dismiss"))
        if (result != ControlResult.Sent) rateLimitRefusalCopy(result)?.let(actions.onRefused)
    }
    Row(
        modifier.widthIn(max = 720.dp).testTag("rate-limit-scheduled"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Icon(TetherIcons.Clock, contentDescription = null, tint = t.muted, modifier = Modifier.size(12.dp).alpha(0.8f))
        Text(
            "Automatic resume scheduled for ${limitClockTime(view.resumeAt, locale, zone)}.",
            style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.72f)),
            color = t.muted,
            modifier = Modifier.alpha(0.8f).weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
        )
        StaleTapGuard(identity) { guard ->
            Box(
                guard
                    .size(TetherDimens.touchTargetDp)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = tap)
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        role = Role.Button
                    }
                    .testTag("rate-limit-cancel"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            }
        }
    }
}
