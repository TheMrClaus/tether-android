package com.tether.app.ui.inspector

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.tether.app.ui.chat.LinkOpener
import com.tether.app.ui.chat.isSafeHref
import com.tether.app.ui.chat.openChatLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URISyntaxException
import java.util.regex.Pattern

/**
 * ta-coik.18: the pull request's address as the web's `<a href={cr.url}>` (repository-panel.tsx:56,
 * tether 90fbb9f). Any non-empty `cr.url` is a link there; the one rule is React's: a `javascript:`
 * URL is blocked ([REACT_JAVASCRIPT_URL]), and stays plain text here. The browser then reads the
 * href with its URL parser, as this does:
 * - leading and trailing C0 controls and spaces are dropped, tabs and newlines anywhere removed;
 * - a relative address resolves against the console ([consoleOrigin], the paired server's origin,
 *   the page the web's link sits on), and an `http(s)` one is parsed the same way;
 * - any other scheme is kept as written, its scheme lowercased (a URL's scheme is case-insensitive;
 *   Android matches an intent's scheme exactly).
 *
 * Null when the web draws plain text (no address, or a blocked one), or when a relative address has
 * no console to resolve against.
 */
// TrimLambda: the URL parser strips C0 controls and space (U+0000-U+0020), not trim()'s Unicode whitespace.
@SuppressLint("TrimLambda")
internal fun pullRequestHref(raw: String?, consoleOrigin: String?): String? {
    if (raw.isNullOrEmpty()) return null
    if (REACT_JAVASCRIPT_URL.matcher(raw).lookingAt()) return null
    val href = raw.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
    val scheme = urlScheme(href)
    if (scheme != null && scheme != "http" && scheme != "https") return scheme + href.substring(scheme.length)
    val base = consoleOrigin?.let { "$it/".toHttpUrlOrNull() }
    val resolved = if (base != null) base.resolve(href) else href.toHttpUrlOrNull()
    return resolved?.toString() ?: href.takeIf { scheme != null }
}

/**
 * React DOM's `isJavaScriptProtocol` (react-dom-client, `sanitizeURL`), byte for byte: leading C0
 * controls or spaces, then `javascript:` with tabs and newlines allowed between its letters. JS `/i`
 * without `u` folds ASCII letters only, so no UNICODE_CASE (Kotlin's IGNORE_CASE would add it).
 */
private val REACT_JAVASCRIPT_URL: Pattern = Pattern.compile(
    "^[\\u0000-\\u001F ]*j[\\r\\n\\t]*a[\\r\\n\\t]*v[\\r\\n\\t]*a[\\r\\n\\t]*s[\\r\\n\\t]*c[\\r\\n\\t]*r[\\r\\n\\t]*i[\\r\\n\\t]*p[\\r\\n\\t]*t[\\r\\n\\t]*:",
    Pattern.CASE_INSENSITIVE,
)

/** The URL parser's scheme state: an ASCII letter, then letters, digits, `+`, `-` or `.`, up to `:`. Lowercased. */
private fun urlScheme(href: String): String? {
    val colon = href.indexOf(':')
    if (colon < 1) return null
    val scheme = href.substring(0, colon)
    if (!scheme[0].isAsciiLetter()) return null
    if (!scheme.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return null
    return scheme.lowercase()
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

/** What the browser itself navigates to and never hands an app (Chrome refuses these from a web page's link). */
private val BROWSER_ONLY_SCHEMES = setOf("file", "content", "data", "blob", "filesystem")

/** Shown under the pull request when nothing on the phone took its link (the browser's own failure, said). */
internal const val PULL_REQUEST_UNOPENED = "No app on this phone could open the link."

/**
 * Opens [href] (a [pullRequestHref]) as the web's link does. A web or mail address takes the chat
 * links' path ([openChatLink] through [opener]: a Custom Tab, or the app for a session on the paired
 * server), unchanged. Any other scheme is offered to the phone's apps as a browser hands it on:
 * `ACTION_VIEW` + `CATEGORY_BROWSABLE` (only activities that say a link may open them); an `intent:`
 * address with Chrome's sanitising (browsable only, no explicit component or selector component, no
 * flags it carries), falling back to its `browser_fallback_url` when no app takes it, as Chrome does.
 *
 * Returns false when nothing opened it, as a browser fails on a scheme nothing handles.
 */
fun openPullRequestLink(context: Context, opener: LinkOpener, href: String, toolbarColor: Color): Boolean {
    if (isSafeHref(href)) return openChatLink(context, opener, href, toolbarColor)
    val intent = pullRequestIntent(href) ?: return false
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        val fallback = intent.getStringExtra(BROWSER_FALLBACK_URL)
        val web = fallback?.let(::urlScheme).let { it == "http" || it == "https" }
        if (href.startsWith("intent:") && web) openChatLink(context, opener, fallback!!, toolbarColor) else false
    } catch (_: SecurityException) {
        false
    }
}

private const val BROWSER_FALLBACK_URL = "browser_fallback_url"

/** The intent a browser would hand on for [href] (a non-web scheme), or null when it hands on none. */
@SuppressLint("UseKtx")
internal fun pullRequestIntent(href: String): Intent? {
    val scheme = urlScheme(href) ?: return null
    val intent = if (scheme == "intent") {
        try {
            Intent.parseUri(href, Intent.URI_INTENT_SCHEME)
        } catch (_: URISyntaxException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }.apply {
            component = null
            flags = 0
            selector?.let { s ->
                s.addCategory(Intent.CATEGORY_BROWSABLE)
                s.component = null
            }
        }
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse(href))
    }
    // A local-file, content or in-page address goes nowhere, nor an `intent:` carrying one (and a
    // file:// one would be refused by StrictMode's file-URI rule).
    if (listOfNotNull(intent.data, intent.selector?.data).any { it.scheme?.lowercase() in BROWSER_ONLY_SCHEMES }) return null
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    return intent
}
