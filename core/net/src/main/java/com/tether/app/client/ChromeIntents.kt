package com.tether.app.client

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * ta-coik.18 r2: an `intent:` link opened the way Chrome on Android opens a page's one
 * (Chromium ExternalNavigationHandler), shared by every place the app hands such a link on (the
 * inspector's pull request, the Claude login link) so the two cannot drift:
 * - read with `Intent.parseUri(URI_INTENT_SCHEME)`; a link that does not parse (any exception
 *   parseUri throws) opens nothing;
 * - refused when its data is a `content:` or `file:` address (Chrome's own checks; `data:`, `blob:`
 *   or `filesystem:` data is not refused);
 * - sanitised as `sanitizeQueryIntentActivitiesIntent` does: browsable only, no explicit component,
 *   no selector (crbug 1254422), the flags masked to [ALLOWED_INTENT_FLAGS] (so no URI grant);
 * - when no app takes it: its `browser_fallback_url` (http or https) in the browser, else, when it
 *   names a package, that package's store page (`market://details?id=`, or the Play web page when
 *   no store app takes that), as Chrome does.
 */
object ChromeIntents {
    /** Chrome's ExternalNavigationHandler ALLOWED_INTENT_FLAGS; every other flag of a parsed link is dropped. */
    @SuppressLint("InlinedApi")
    const val ALLOWED_INTENT_FLAGS: Int = Intent.FLAG_EXCLUDE_STOPPED_PACKAGES or Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_MATCH_EXTERNAL or Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS or
        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT

    /** The data schemes Chrome refuses in an `intent:` link. */
    val REFUSED_DATA_SCHEMES: Set<String> = setOf("content", "file")

    const val EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url"

    /** Whether [href] is an `intent:` link (ASCII case-insensitive, as a URL scheme is). */
    fun isIntentLink(href: String): Boolean = href.length >= 7 && href.regionMatches(0, "intent:", 0, 7, ignoreCase = true) &&
        href.take(7).all { it.code < 128 }

    /** The sanitised intent Chrome starts for the `intent:` link [href], or null when it starts none. */
    fun parse(href: String): Intent? {
        val intent = try {
            Intent.parseUri(href, Intent.URI_INTENT_SCHEME)
        } catch (_: Exception) {
            // parseUri throws more than URISyntaxException for a malformed link (NumberFormatException
            // for `launchFlags=zz` or `i.k=x`, and others): any of them is a link that does not parse.
            return null
        }
        if (intent.data?.scheme?.lowercase(Locale.ROOT) in REFUSED_DATA_SCHEMES) return null
        return sanitize(intent)
    }

    /**
     * Chrome's sanitizeQueryIntentActivitiesIntent on [intent], in place: browsable only, no explicit
     * component, no selector, the flags masked to [ALLOWED_INTENT_FLAGS]. (parseUri already drops the
     * URI-grant flags of a link; the mask holds whatever the intent's source.)
     */
    fun sanitize(intent: Intent): Intent {
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.flags = intent.flags and ALLOWED_INTENT_FLAGS
        return intent
    }

    /** What Chrome opens when no app takes [intent] (a [parse] result). */
    sealed interface Fallback {
        /** The link's own `browser_fallback_url`, an http(s) address. */
        data class Web(val url: String) : Fallback

        /** The store page of the package the link names. */
        data class Store(val packageName: String) : Fallback {
            val marketUrl: String get() = "market://details?id=" + Uri.encode(packageName)
            val webUrl: String get() = "https://play.google.com/store/apps/details?id=" + Uri.encode(packageName)
        }
    }

    fun fallback(intent: Intent): Fallback? {
        val url = try {
            intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL)
        } catch (_: RuntimeException) {
            null
        }
        val scheme = url?.substringBefore(':', "")?.lowercase(Locale.ROOT)
        if (url != null && (scheme == "http" || scheme == "https")) return Fallback.Web(url)
        return intent.`package`?.takeIf { it.isNotEmpty() }?.let(Fallback::Store)
    }

    /**
     * Opens the `intent:` link [href] as Chrome does (see the object); [openWeb] opens an http(s)
     * fallback in the browser, the caller's own way. [newTask]: start in a new task (a non-Activity
     * context needs it). False when nothing opened.
     */
    fun open(context: Context, href: String, newTask: Boolean = context !is Activity, openWeb: (String) -> Boolean): Boolean {
        val intent = parse(href) ?: return false
        if (start(context, intent, newTask)) return true
        return when (val fallback = fallback(intent)) {
            is Fallback.Web -> openWeb(fallback.url)
            is Fallback.Store -> start(context, view(fallback.marketUrl), newTask) || openWeb(fallback.webUrl)
            null -> false
        }
    }

    /** `ACTION_VIEW` + `CATEGORY_BROWSABLE` on [href]: what a browser hands an app for a page's link. */
    @SuppressLint("UseKtx")
    fun view(href: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(href)).addCategory(Intent.CATEGORY_BROWSABLE)

    /** Starts [intent]; false (never a crash) when no app takes it or the platform refuses it. */
    fun start(context: Context, intent: Intent, newTask: Boolean = context !is Activity): Boolean {
        if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: RuntimeException) {
            // ActivityNotFoundException, SecurityException, FileUriExposedException and the like.
            false
        }
    }
}
