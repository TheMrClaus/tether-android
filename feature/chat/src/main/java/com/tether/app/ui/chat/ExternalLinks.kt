package com.tether.app.ui.chat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeHref
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.appendStyled
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/**
 * Opens a markdown link the way the web's `<a target="_blank" rel="noopener noreferrer nofollow">`
 * does on a phone: OUTSIDE the app. PLAN §0.1 allows exactly one form for an external URL — a
 * Chrome Custom Tab, Android's native "open link" — and never a WebView.
 */
fun interface LinkOpener {
    fun open(context: Context, href: String, toolbarColor: Color)

    /** ta-fz3: does [href] stay in the app (a session on the paired server)? Such a link is not external and needs no confirmation. */
    fun opensInApp(href: String): Boolean = false
}

/**
 * The Custom Tabs protocol (the extras androidx.browser's `CustomTabsIntent` writes), spoken
 * directly so no library is needed: an ACTION_VIEW intent carrying the [EXTRA_SESSION] binder key
 * (null = no warm-up session) is what makes a Custom-Tabs browser open a tab instead of its full
 * UI; a browser without Custom Tabs just opens the link. `mailto:` goes to the mail app as a
 * plain ACTION_VIEW (a tab makes no sense there).
 */
object CustomTabLinkOpener : LinkOpener {
    const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"
    const val EXTRA_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"

    // Uri.parse, not core-ktx's toUri: this module does not depend on androidx.core.
    @SuppressLint("UseKtx")
    fun intentFor(href: String, toolbarColor: Color): Intent? {
        // markdown.tsx SAFE_HREF (never javascript:/data:/intent:), as ta-fz3's full-string check.
        if (!isSafeHref(href)) return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(href))
        // r2: only activities that declare they may be opened from a link (a browser, a mail app),
        // never an exported non-browsable VIEW handler.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        // ASCII-only fold like the allowlist (JVM ignoreCase would fold Unicode, e.g. `ı` == `i`).
        if (!href.startsWithAsciiIgnoreCase("mailto:")) {
            intent.putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
            intent.putExtra(EXTRA_TOOLBAR_COLOR, toolbarColor.toArgb())
        }
        return intent
    }

    override fun open(context: Context, href: String, toolbarColor: Color) {
        val intent = intentFor(href, toolbarColor) ?: return
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser / mail app: the link stays visible text, like a blocked popup on the web.
        }
    }
}

/** How markdown links open; tests may observe it, production uses [CustomTabLinkOpener]. */
val LocalLinkOpener = staticCompositionLocalOf<LinkOpener> { CustomTabLinkOpener }

// ---- ta-fz3: confirm before an external link opens ---------------------------------------------

/** An external link waiting for the operator's confirmation: what the sheet shows, and the opener it was tapped under. Never saved. */
class PendingLink internal constructor(val target: SafeHref.Target, internal val opener: LinkOpener, val serial: Long)

/** What a tap on a link did. */
enum class LinkDecision {
    /** The href failed [SafeHref]: nothing happened (the parser already draws such a label as inert text). */
    Refused,

    /** A session on the paired server: opened in the app. */
    InApp,

    /** The label is exactly the (printable-ASCII) href: opened. */
    Opened,

    /** The confirm sheet is up; nothing opens until its Open key is tapped. */
    Confirm,
}

/**
 * ta-fz3: the one place a tapped chat link is decided. A link only opens without the confirm
 * sheet when [SafeHref.opensDirectly] allows it (an exact printable-ASCII label, a short host, no
 * `@`) and the caller does not force a confirmation (r2: a clamped block, or a link that appeared
 * or moved within the arm delay); every other external link only opens from the confirm sheet
 * ([ExternalLinkConfirmDialog]): an explicit tap on its armed Open key. What opens is exactly what
 * the sheet showed ([SafeHref.Target.display]: the host in ASCII). There is no auto-open and no
 * retry: a confirmation opens once, or not at all.
 *
 * The pending link is plain memory (never saved): [ExternalLinkConfirmHost] drops it when the
 * activity pauses or stops (screen lock included) and when the host leaves the composition (Lock /
 * sign-out); the host that provides the gate makes a new one per server, so a server switch drops
 * it too.
 */
@Stable
class ExternalLinkGate {
    var pending: PendingLink? by mutableStateOf(null)
        private set
    private var serial = 0L

    /**
     * A tap on [href] drawn with [label]. [forceConfirm] (r2): the link sits where the screen may
     * mislead (a clamped block, or it appeared or moved under the finger within the arm delay), so
     * even a link that could open directly asks first.
     */
    fun request(context: Context, opener: LinkOpener, href: String, label: String, toolbarColor: Color, forceConfirm: Boolean = false): LinkDecision {
        val target = SafeHref.target(href) ?: return LinkDecision.Refused
        if (opener.opensInApp(target.display)) {
            pending = null
            opener.open(context, target.display, toolbarColor)
            return LinkDecision.InApp
        }
        if (!forceConfirm && SafeHref.opensDirectly(target, label)) {
            pending = null
            opener.open(context, target.display, toolbarColor)
            return LinkDecision.Opened
        }
        pending = PendingLink(target, opener, ++serial)
        return LinkDecision.Confirm
    }

    /** The Open key: opens [link] if it is still the pending one (once), and closes the sheet. */
    fun confirm(link: PendingLink, context: Context, toolbarColor: Color): Boolean {
        if (pending !== link) return false
        pending = null
        // What opens is what was shown, and only while it still passes the rule.
        if (SafeHref.target(link.target.display)?.display != link.target.display) return false
        link.opener.open(context, link.target.display, toolbarColor)
        return true
    }

    fun cancel() {
        pending = null
    }
}

/**
 * r2: when a markdown body last appeared or moved in its window (more than [CONTROL_REARM_MOVE_DP]):
 * a link in it opens directly only [CONSENT_ARM_DELAY_MS] after that, so a tap aimed at what was
 * there a moment ago (text streaming in, the list following new output) asks first. Plain fields,
 * not state: a scroll never recomposes the body.
 */
internal class LinkSettle(private val clock: () -> Long) {
    private var anchor: Offset? = null
    private var since: Long = clock()

    fun positioned(at: Offset, limitPx: Float) {
        val a = anchor
        if (a == null || kotlin.math.abs(at.x - a.x) > limitPx || kotlin.math.abs(at.y - a.y) > limitPx) {
            anchor = at
            since = clock()
        }
    }

    fun settled(): Boolean = anchor != null && clock() - since >= CONSENT_ARM_DELAY_MS
}

/** The time [LinkSettle] reads ([SystemClock.uptimeMillis]); tests may drive it. */
internal val LocalLinkClock = staticCompositionLocalOf<() -> Long> { { SystemClock.uptimeMillis() } }

/** The gate in scope (UiRoot provides one per signed-in server); a markdown body without one keeps its own. */
val LocalExternalLinkGate = staticCompositionLocalOf<ExternalLinkGate?> { null }

/** Shows [gate]'s confirm sheet while a link is pending, and drops the pending link on pause, stop and leaving the composition. */
@Composable
fun ExternalLinkConfirmHost(gate: ExternalLinkGate) {
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { gate.cancel() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { gate.cancel() }
    DisposableEffect(gate) { onDispose { gate.cancel() } }
    val link = gate.pending ?: return
    val context = LocalContext.current
    val t = LocalTetherTokens.current
    ExternalLinkConfirmDialog(
        target = link.target,
        identity = link.serial,
        onConfirm = { gate.confirm(link, context, t.graphite) },
        onCancel = gate::cancel,
    )
}

const val EXTERNAL_LINK_SHEET_TAG = "external-link-confirm"
const val EXTERNAL_LINK_OPEN_TAG = "external-link-open"
const val EXTERNAL_LINK_CANCEL_TAG = "external-link-cancel"
const val EXTERNAL_LINK_HOST_TAG = "external-link-host"
const val EXTERNAL_LINK_PORT_TAG = "external-link-port"
const val EXTERNAL_LINK_TO_TAG = "external-link-to"
const val EXTERNAL_LINK_TARGET_TAG = "external-link-target"

internal const val EXTERNAL_LINK_BODY = "This link leaves Tether. Check where it goes before you open it."
internal const val EXTERNAL_MAIL_BODY = "This link opens your mail app. Check who it writes to before you open it."
internal const val EXTERNAL_LINK_IDN_NOTE = "International domain name, shown in its ASCII (punycode) form."

/**
 * ta-fz3: the confirm sheet (the program's confirm dialog, [TetherDialog]). It shows where the
 * link goes, never its label: the host in ASCII ([SafeHref.Target.host], punycode for an
 * international name) with a note when it was one, the port when the href names one, the mailto
 * recipients, and the whole target, each drawn by the CODE rule, in mono, forced left to right
 * character by character ([SafeHref.forcedLtr]), wrapping anywhere. Cancel closes it; the Open key
 * is an operator control like End session: armed [CONSENT_ARM_DELAY_MS] after the sheet appeared
 * for [identity] and again after it moved, touches through an overlay refused.
 */
@Composable
fun ExternalLinkConfirmDialog(
    target: SafeHref.Target,
    /** What the sheet was opened for (a fresh tap is a fresh identity): the arming's identity. */
    identity: Any,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val arming = rememberArmedControl(identity, actionable = true)
    val mail = target.scheme == SafeHref.Scheme.Mailto
    TetherDialog(
        onDismiss = onCancel,
        title = if (mail) "Write this email?" else "Open this link?",
        footer = {
            TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel", modifier = Modifier.testTag(EXTERNAL_LINK_CANCEL_TAG))
            TetherKey(
                onClick = { if (arming.armed) onConfirm() },
                classes = KeyClasses.ButtonPrimary,
                label = if (mail) "Open mail app" else "Open link",
                icon = if (mail) TetherIcons.Mail else TetherIcons.ExternalLink,
                enabled = arming.armed,
                modifier = arming.modifier.testTag(EXTERNAL_LINK_OPEN_TAG),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(EXTERNAL_LINK_SHEET_TAG), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TetherDialogText(if (mail) EXTERNAL_MAIL_BODY else EXTERNAL_LINK_BODY)
            // Forced LTR, also in an RTL UI: the fields read left to right like the address they show.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    target.host?.let { LinkField("Host", it, EXTERNAL_LINK_HOST_TAG) }
                    target.port?.let { LinkField("Port", it.toString(), EXTERNAL_LINK_PORT_TAG) }
                    if (target.recipients.isNotEmpty()) LinkField("To", target.recipients.joinToString("\n"), EXTERNAL_LINK_TO_TAG)
                    if (target.international) TetherDialogText(EXTERNAL_LINK_IDN_NOTE)
                    LinkField(if (mail) "Address" else "Link", target.display, EXTERNAL_LINK_TARGET_TAG)
                }
            }
        }
    }
}

/** One labelled value of the sheet: [value] by the code rule, mono, forced LTR; TalkBack reads "label: value". */
@Composable
private fun LinkField(label: String, value: String, tag: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val drawn = forcedLtrCode(value)
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "$label: $value"
                testTag = tag
            },
    ) {
        Text(label, color = t.muted, style = type.body.copy(fontSize = 11.5.sp))
        Text(
            drawn,
            color = t.ink,
            style = type.body.copy(fontFamily = type.mono, fontSize = 12.8.sp, textDirection = TextDirection.Ltr),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** [text] by the code rule (tokens styled), breakable anywhere, then forced LTR ([SafeHref.forcedLtr]). */
@Composable
private fun forcedLtrCode(text: String): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(text, t) {
        val display = SafeText.breakAnywhere(SafeText.code(text))
        AnnotatedString.Builder(display.length + 2).apply {
            withStyle(ParagraphStyle(textDirection = TextDirection.Ltr)) { appendStyled(SafeHref.forcedLtr(display), tokenStyle(t)) }
        }.toAnnotatedString()
    }
}
