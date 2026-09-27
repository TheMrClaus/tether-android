package com.tether.app.push

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The push kinds the server raises (tether `lib/push-notifications.mjs`,
 * `createPushEventObserver`). Each visible kind has its own notification channel
 * ([PushChannels]). [Other] covers a kind this build does not know yet: it is
 * still shown, on the general channel, the same way the web service worker shows
 * any push it receives.
 */
enum class PushKind(val wire: String) {
    /** An approval request still pending after the server's human-ask window. */
    Approval("approval"),

    /** A question still pending after the same window. */
    Question("question"),

    /** A turn ended (`turn_end`). The server tags it `tether-complete-…`. */
    TurnEnd("turn_end"),

    /** A rate limit asks whether to resume when it resets (`resume_choice`). */
    ResumeChoice("resume_choice"),

    Other("");

    companion object {
        fun fromWire(value: String?): PushKind =
            entries.firstOrNull { it != Other && it.wire == value } ?: Other
    }
}

/** What one FCM message means to the app. Built by [PushMessageParser.parse]. */
sealed interface PushMessage {
    /**
     * The content-free background-sync hint (server v130, SYNC_DESIGN §6.1 B):
     * data-only `{kind:"sync", v:"1"}`. Carries nothing else, and nothing else is
     * ever read from it (SYNC_DESIGN §6.3). It never posts a notification.
     */
    data object SyncHint : PushMessage

    /**
     * A notification to show. It carries no session: a tap only opens the app
     * (see [PushDeepLink]).
     */
    data class Visible(
        val kind: PushKind,
        val title: String,
        val body: String,
        val tag: String?,
    ) : PushMessage

    /** Dropped without a notification (for example, no title or no body). */
    data object Ignored : PushMessage
}

/**
 * Maps an FCM message onto a [PushMessage]. Pure (no Android types), so every
 * rule is JVM-tested.
 *
 * Rules, in order:
 * 1. `data.kind == "sync"` is a [PushMessage.SyncHint], whatever else the message
 *    carries. This check runs before the title check, so a hint can never show a
 *    notification, not even a blank one.
 * 2. A visible notification needs a non-blank title and body. They come from the
 *    FCM `notification` block, which is what the server sends. A data-only
 *    message with `data.title`/`data.body` is also accepted, as before T12.1. With
 *    neither, the message is [PushMessage.Ignored]: a blank notification is never
 *    posted.
 * 3. `data.tag` is the server's collapse key (`tether-<kind>-<sha24>`). A tag
 *    outside the expected character set or length is dropped (the notifier falls
 *    back to one shared tag) rather than used as is.
 * 4. `data.url` is not read. The server keeps it id-free (`/`, the FCM privacy
 *    floor), so a session named there could only come from someone else. A tap
 *    just opens the app. T4.4 may route taps to a session, but only with a
 *    verified sender; [sessionIdFromUrl] and [SessionIds] are kept, unwired, for
 *    that.
 */
object PushMessageParser {
    const val SYNC_KIND = "sync"

    /** Cap on what we copy into a notification; the server's copy is one short sentence. */
    private const val MAX_TITLE = 200
    private const val MAX_BODY = 1_000
    private const val MAX_TAG = 128
    private const val MAX_URL = 512
    private val TAG_PATTERN = Regex("^[A-Za-z0-9._:-]{1,$MAX_TAG}$")

    fun parse(
        data: Map<String, String>,
        notificationTitle: String?,
        notificationBody: String?,
    ): PushMessage {
        if (data["kind"] == SYNC_KIND) return PushMessage.SyncHint

        val title = firstNonBlank(notificationTitle, data["title"]) ?: return PushMessage.Ignored
        val body = firstNonBlank(notificationBody, data["body"]) ?: return PushMessage.Ignored
        return PushMessage.Visible(
            kind = PushKind.fromWire(data["kind"]),
            title = title.take(MAX_TITLE),
            body = body.take(MAX_BODY),
            tag = data["tag"]?.takeIf { TAG_PATTERN.matches(it) },
        )
    }

    /**
     * NOT WIRED (T12.1 round 2, security review H1): nothing calls this on the
     * notification path. It is kept, tested, for T4.4 to reuse once a tap's
     * sender can be verified.
     *
     * The session named by a same-origin `/?session=<id>` url, or null. Mirrors
     * the web: the service worker keeps only same-origin paths (`public/sw.js`,
     * `safeTarget`) and the dashboard reads only the `session` parameter
     * (`components/dashboard.tsx`). Anything else (another path, a scheme, `//`,
     * an invalid id) means "open the app on its current screen".
     */
    fun sessionIdFromUrl(url: String?): String? {
        if (url == null || url.length > MAX_URL) return null
        if (!url.startsWith("/") || url.startsWith("//")) return null
        val withoutFragment = url.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        if (path != "/") return null
        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        val raw = query.split('&')
            .firstOrNull { it.substringBefore('=') == "session" }
            ?.substringAfter('=', missingDelimiterValue = "")
            ?: return null
        val decoded = try {
            URLDecoder.decode(raw, StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return decoded.takeIf(SessionIds::isValid)
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}

/**
 * The shape a session id must have before a push or an intent may select it.
 * The server bounds ids to 128 characters on the wire
 * (`lib/protocol-validate.mjs`, `LIMITS.ID_LENGTH`). This also limits the
 * character set to what real ids use (UUIDs, `provider:uuid`, `sess-1`), so a
 * path, a url, whitespace or a control character never reaches navigation.
 */
object SessionIds {
    private val PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    fun isValid(id: String?): Boolean = id != null && PATTERN.matches(id)
}
