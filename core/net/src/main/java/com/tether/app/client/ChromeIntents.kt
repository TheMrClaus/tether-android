package com.tether.app.client

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * ta-coik.18: an `intent:` link opened the way Chrome on Android opens a page's one (Chromium
 * ExternalNavigationHandler), shared by every place the app hands such a link on (the inspector's
 * pull request, the Claude login link) so the two cannot drift:
 * - read with `Intent.parseUri(URI_INTENT_SCHEME)`; a link that does not parse (any exception
 *   parseUri throws) opens nothing;
 * - refused when its data is an address Chrome ignores ([REFUSED_DATA_SCHEMES]; `data:`, `blob:`,
 *   `filesystem:` or `javascript:` data is not refused);
 * - sanitised as `sanitizeQueryIntentActivitiesIntent` does: browsable only, no explicit component,
 *   no selector (crbug 1254422), the flags masked to [ALLOWED_INTENT_FLAGS] (so no URI grant);
 * - its `browser_fallback_url` read and removed before it goes out (r3);
 * - refused when it would resolve to one of this app's own non-exported activities (Chrome's
 *   `resolvesToNonExportedActivity`, r3); a refused link (its data or this check) still opens its
 *   http(s) `browser_fallback_url`, never the store (r4);
 * - when no app takes it: its `browser_fallback_url` (http or https, in its canonical form) in the
 *   browser, else, when it names a package, that package's store page (`market://details?id=` on the
 *   Play Store app, as Chrome does; then the Play web page, kept on purpose as more than Chrome).
 */
object ChromeIntents {
    /** Chrome's ExternalNavigationHandler ALLOWED_INTENT_FLAGS; every other flag of a parsed link is dropped. */
    @SuppressLint("InlinedApi")
    const val ALLOWED_INTENT_FLAGS: Int = Intent.FLAG_EXCLUDE_STOPPED_PACKAGES or Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_MATCH_EXTERNAL or Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS or
        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT

    /** The schemes Chrome ignores for an external app, as a link's own scheme and as an intent's data. */
    private val CHROME_IGNORED_SCHEMES: Set<String> = setOf("about", "chrome", "chrome-native", "devtools", "fido")

    /** The data schemes Chrome refuses in an `intent:` link (lowercase; compare a lowercased scheme). */
    val REFUSED_DATA_SCHEMES: Set<String> = setOf("content", "file") + CHROME_IGNORED_SCHEMES

    /**
     * r3: the schemes of a page's link the browser never hands an app (lowercase; compare a
     * lowercased scheme): local files and content, in-page data and blobs, and Chrome's ignored
     * schemes. Shared by the inspector's pull request link and the Claude login link.
     */
    val BROWSER_ONLY_SCHEMES: Set<String> = setOf("file", "content", "data", "blob", "filesystem") + CHROME_IGNORED_SCHEMES

    const val EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url"
    const val EXTRA_MARKET_REFERRER = "market_referrer"

    /** Chrome pins its store redirect to the Play Store app. */
    const val PLAY_STORE_PACKAGE = "com.android.vending"

    /** Whether [href] is an `intent:` link (ASCII case-insensitive, as a URL scheme is). */
    fun isIntentLink(href: String): Boolean = href.length >= 7 && href.regionMatches(0, "intent:", 0, 7, ignoreCase = true) &&
        href.take(7).all { it.code < 128 }

    /** The sanitised intent Chrome starts for the `intent:` link [href], or null when it starts none. */
    fun parse(href: String): Intent? {
        val intent = parseUri(href) ?: return null
        if (refusedData(intent)) return null
        return sanitize(intent)
    }

    /** The `intent:` link as parseUri reads it, unsanitised; null when it does not parse. */
    private fun parseUri(href: String): Intent? = try {
        Intent.parseUri(href, Intent.URI_INTENT_SCHEME)
    } catch (_: Exception) {
        // parseUri throws more than URISyntaxException for a malformed link (NumberFormatException
        // for `launchFlags=zz` or `i.k=x`, and others): any of them is a link that does not parse.
        null
    }

    private fun refusedData(intent: Intent): Boolean = intent.data?.scheme?.lowercase(Locale.ROOT) in REFUSED_DATA_SCHEMES

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

    /** What Chrome opens when no app takes a parsed link. */
    sealed interface Fallback {
        /** The link's own `browser_fallback_url`: an http(s) address, canonical. */
        data class Web(val url: String) : Fallback

        /** The store page of the package the link names; [referrer] is the link's `market_referrer`. */
        data class Store(val packageName: String, val referrer: String? = null) : Fallback {
            /**
             * Chrome's: `market://details?id=<package>&referrer=<the link's market_referrer as parsed
             * (an empty one stays empty), else the opener's package>`.
             */
            fun marketUrl(defaultReferrer: String): String = Uri.Builder().scheme("market").authority("details")
                .appendQueryParameter("id", packageName)
                .appendQueryParameter("referrer", referrer ?: defaultReferrer)
                .build().toString()

            /**
             * The Play web page, tried when no store app takes [marketUrl]. Chrome stops at the store
             * intent; this is kept on purpose as more than Chrome does (a phone without the Play Store
             * still reaches the page), never less.
             */
            val webUrl: String get() = "https://play.google.com/store/apps/details?id=" + Uri.encode(packageName)
        }
    }

    /**
     * Reads [intent]'s fallback (see [Fallback]) and removes `browser_fallback_url` from it, so the
     * app that takes the intent never receives it (r3, as Chrome does).
     */
    fun takeFallback(intent: Intent): Fallback? {
        val raw = extra(intent, EXTRA_BROWSER_FALLBACK_URL)
        intent.removeExtra(EXTRA_BROWSER_FALLBACK_URL)
        webFallback(raw)?.let { return Fallback.Web(it) }
        return intent.`package`?.takeIf { it.isNotEmpty() }?.let { Fallback.Store(it, extra(intent, EXTRA_MARKET_REFERRER)) }
    }

    /**
     * r3: the fallback address as the URL parser reads it (outer C0 controls and spaces dropped,
     * tabs and newlines removed), kept when it is an http(s) URL, in its canonical form.
     */
    @SuppressLint("TrimLambda") // the URL parser strips U+0000-U+0020, not trim()'s Unicode whitespace
    internal fun webFallback(raw: String?): String? {
        val cleaned = raw?.trim { it <= ' ' }?.filterNot { it == '\t' || it == '\n' || it == '\r' } ?: return null
        return cleaned.toHttpUrlOrNull()?.toString()
    }

    private fun extra(intent: Intent, key: String): String? = try {
        intent.getStringExtra(key)
    } catch (_: RuntimeException) {
        null
    }

    /**
     * r3: Chrome's resolvesToNonExportedActivity: whether [intent] would resolve to an activity of
     * this app that is not exported (a page's link must never reach the app's own internals). Queried
     * as Chrome queries (GET_RESOLVED_FILTER | MATCH_DEFAULT_ONLY). r4: a query that throws counts as
     * one that does (fail closed: nothing starts, the link's web fallback may still open).
     */
    // QueryPermissionsNeeded: only this app's own activities matter here, and an app always sees its own.
    @SuppressLint("QueryPermissionsNeeded")
    fun resolvesToNonExportedActivity(context: Context, intent: Intent): Boolean = try {
        context.packageManager.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER or PackageManager.MATCH_DEFAULT_ONLY).any { info ->
            val activity = info.activityInfo
            activity != null && activity.packageName == context.packageName && !activity.exported
        }
    } catch (_: RuntimeException) {
        true
    }

    /**
     * Opens the `intent:` link [href] as Chrome does (see the object); [openWeb] opens an http(s)
     * fallback in the browser, the caller's own way. [newTask]: start in a new task (a non-Activity
     * context needs it). False when nothing opened.
     */
    fun open(context: Context, href: String, newTask: Boolean = context !is Activity, openWeb: (String) -> Boolean): Boolean {
        val intent = parseUri(href) ?: return false
        val fallback = takeFallback(intent)
        // r4 (ENH handleFallbackUrl on every "no override" once the link parses): a refused link
        // still opens its web fallback; never the store, as Chrome.
        val webOnly = { (fallback as? Fallback.Web)?.let { openWeb(it.url) } ?: false }
        if (refusedData(intent)) return webOnly()
        sanitize(intent)
        if (resolvesToNonExportedActivity(context, intent)) return webOnly()
        if (start(context, intent, newTask)) return true
        return when (fallback) {
            is Fallback.Web -> openWeb(fallback.url)
            is Fallback.Store -> start(context, store(fallback.marketUrl(context.packageName)), newTask) || openWeb(fallback.webUrl)
            null -> false
        }
    }

    /** The store intent Chrome starts: the market address on the Play Store app. */
    fun store(marketUrl: String): Intent = view(marketUrl).setPackage(PLAY_STORE_PACKAGE)

    /**
     * r4 (ta-qap9): a page's plain link [href] (not `intent:`) handed to the app that takes it, as
     * Chrome hands it on: [view], refused when it would reach this app's own non-exported activity
     * (such a link has no fallback). False when nothing opened.
     */
    fun openView(context: Context, href: String, newTask: Boolean = context !is Activity): Boolean {
        val intent = view(href)
        if (resolvesToNonExportedActivity(context, intent)) return false
        return start(context, intent, newTask)
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
