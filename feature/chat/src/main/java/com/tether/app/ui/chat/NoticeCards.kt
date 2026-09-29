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
import androidx.compose.ui.semantics.disabled
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
 * the limit card only from a tap on one of its keys; each sends at most once per link (a latch here,
 * the client's own checks under its lock), never in answer to anything received, and shows why in
 * words when it cannot act. Nothing is retried.
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/** Why no notice of this session can be dismissed right now (only the link can stop it). */
enum class NoticeLock(val copy: String) {
    Offline("Connect to dismiss this notice."),
    CatchingUp("Catching up… You can dismiss this notice once the latest state is in."),
}

/**
 * Dismissal is presentation-only and the server allows it on a read-only or handed-off session, so
 * only the link and the session's liveness lock it (never [ConsentLock.ReadOnly] / HandedOff).
 */
fun noticeLock(connected: Boolean, live: Boolean): NoticeLock? = when {
    !connected -> NoticeLock.Offline
    !live -> NoticeLock.CatchingUp
    else -> null
}

/**
 * What the notices and the limit card of one session may do. [onDismiss] and [onRateLimit] are the
 * ONLY ways they reach the wire. [link] names the connection they were drawn on: a latch set on one
 * link never holds a key disabled on the next.
 */
@Immutable
class NoticeActions(
    val sessionId: String?,
    val lock: NoticeLock?,
    /** The rate-limit choices change what the agent does: T7.2's lock (read-only / handed off too). */
    val controlLock: ConsentLock?,
    val link: Any?,
    internal val onDismiss: (dismissKey: String) -> NoticeResult,
    internal val onRateLimit: (SessionControl.RateLimitResume) -> ControlResult,
    /** Says a refusal in words (the screen's error toast). */
    internal val onRefused: (String) -> Unit = {},
) {
    companion object {
        /** Fail closed: every X and key renders disabled and nothing is sent. */
        val Unavailable = NoticeActions(
            sessionId = null,
            lock = NoticeLock.Offline,
            controlLock = ConsentLock.Offline,
            link = null,
            onDismiss = { NoticeResult.NotConnected },
            onRateLimit = { ControlResult.NotConnected },
        )
    }
}

/** The notices read their session's actions here. */
val LocalNoticeActions = compositionLocalOf { NoticeActions.Unavailable }

/** The words for a dismissal that sent nothing (null: nothing to say — the notice is already gone, or the client said it). */
internal fun noticeRefusalCopy(result: NoticeResult): String? = when (result) {
    NoticeResult.Sent, NoticeResult.AlreadySent, NoticeResult.NotShown, NoticeResult.NotConnected -> null
    NoticeResult.NotLive -> "Catching up — the notice was not dismissed. Try again in a moment."
    NoticeResult.Locked -> "This session can’t be changed from here."
}

/** The words for a limit choice that sent nothing. */
internal fun rateLimitRefusalCopy(result: ControlResult): String? = when (result) {
    ControlResult.Sent, ControlResult.NotConnected -> null
    ControlResult.NotLive -> "Catching up — nothing was sent. Try again in a moment."
    ControlResult.Locked -> "This session can’t be changed from here."
    ControlResult.NotOffered -> "That limit prompt is no longer active — nothing was sent."
    ControlResult.NeedsConfirmation -> null
}

/**
 * chat-view.tsx clockTime: `Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit",
 * timeZoneName: "short" })` — the locale's short time plus the zone's short name ("3:07 PM UTC").
 */
internal fun limitClockTime(epochMs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
    val formatter = DateTimeFormatter.ofPattern("$pattern z", locale)
    return formatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

/**
 * `NoticeDismissButton` (`.chat-continuation-cancel.notice-dismiss`): the 13px X in a 44dp square
 * (the coarse-pointer hit area), `title` + `aria-label` = [label]. One tap sends one dismissal; the
 * key then stays disabled until the notice folds away (or the link changes).
 */
@Composable
internal fun NoticeDismissButton(dismissKey: String, label: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val actions = LocalNoticeActions.current
    var latched by remember(dismissKey, actions.link) { mutableStateOf(false) }
    val lock = actions.lock
    val enabled = lock == null && !latched
    val tap = {
        if (!latched && actions.lock == null) {
            latched = true
            val result = actions.onDismiss(dismissKey)
            if (result != NoticeResult.Sent && result != NoticeResult.AlreadySent) {
                latched = false
                noticeRefusalCopy(result)?.let(actions.onRefused)
            }
        }
    }
    Box(
        modifier
            .size(TetherDimens.touchTargetDp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = tap)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
                when {
                    lock != null -> stateDescription = lock.copy
                    latched -> stateDescription = "Dismissing…"
                }
                if (!enabled) disabled()
            }
            .testTag("notice-dismiss"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp).alpha(if (enabled) 1f else 0.5f))
    }
}

/**
 * One `CodexNotices` row (`.notice` + `.notice_<level>`): the level's icon (CircleDot for info, the
 * triangle otherwise, named for TalkBack), the bold uppercase lead-in over the message, the X.
 * `role="alert"` for an error, `status` otherwise. The level also rides in words for TalkBack.
 */
@Composable
internal fun ProviderNoticeRow(view: ProviderNoticeView, modifier: Modifier = Modifier) {
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
 * auto-continue · <time>, Resume now, Dismiss. Each is bound to THIS prompt's `resetsAt`, armed like
 * an approval (no tap on a card that just appeared), refused under an overlay, and sends once per
 * link: the card then says the choice was sent and waits for the server's event to remove it.
 * Stricter than the web, which re-enables its keys after 4 s; here a new link re-arms them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RateLimitCard(view: RateLimitPromptView, modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val actions = LocalNoticeActions.current
    val studio = isStudio(t)
    val shape = RoundedCornerShape(if (studio) 14.dp else t.radiusMd)
    val identity = view.resetsAt to actions.link
    var sent by remember(identity) { mutableStateOf<String?>(null) }
    var overlayBlocked by remember(identity) { mutableStateOf(false) }
    val lock = actions.controlLock
    val actionable = sent == null && lock == null
    val armed = rememberArmed(identity, actionable)
    val resetClock = limitClockTime(view.resetsAt, locale, zone)
    val resumeClock = limitClockTime(view.resumeAt, locale, zone)

    fun choose(action: String) {
        if (sent != null || !armed || actions.controlLock != null) return
        sent = action
        val result = actions.onRateLimit(SessionControl.RateLimitResume(view.resetsAt, action))
        if (result != ControlResult.Sent) {
            sent = null
            rateLimitRefusalCopy(result)?.let(actions.onRefused)
        }
    }
    val blocked = { overlayBlocked = true }

    Column(
        modifier
            .fillMaxWidth()
            .cssSurface(
                shape,
                background = t.attentionBg,
                border = CssBorder(1.dp, t.attentionBorder),
                shadows = if (studio) emptyList() else listOf(hardShadow(1.dp, t.litSoft, inset = true)) + t.css.shadowFloating,
            )
            .padding(if (studio) 20.dp else t.css.spaceLg)
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
            lock != null && sent == null -> lock.copy.replace("answer", "choose")
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

/**
 * The scheduled resume (`.chat-continuation`, `role="status"`): Clock, "Automatic resume scheduled
 * for <time>.", and the cancel X ("Cancel scheduled resume"), which sends `dismiss` for this
 * prompt's `resetsAt` — it cancels a turn the server would start, so it takes T7.2's guarded path
 * and lock (a read-only or handed-off session cannot cancel from here).
 */
@Composable
internal fun ScheduledResumeRow(view: RateLimitPromptView, modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val actions = LocalNoticeActions.current
    var latched by remember(view.resetsAt, actions.link) { mutableStateOf(false) }
    val lock = actions.controlLock
    val enabled = lock == null && !latched
    val label = "Cancel scheduled resume"
    val tap = {
        if (!latched && actions.controlLock == null) {
            latched = true
            val result = actions.onRateLimit(SessionControl.RateLimitResume(view.resetsAt, "dismiss"))
            if (result != ControlResult.Sent) {
                latched = false
                rateLimitRefusalCopy(result)?.let(actions.onRefused)
            }
        }
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
        Box(
            Modifier
                .size(TetherDimens.touchTargetDp)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = tap)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    role = Role.Button
                    when {
                        lock != null -> stateDescription = controlLockCopy(lock) ?: lock.copy
                        latched -> stateDescription = "Cancelling…"
                    }
                    if (!enabled) disabled()
                }
                .testTag("rate-limit-cancel"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp).alpha(if (enabled) 1f else 0.5f))
        }
    }
}
