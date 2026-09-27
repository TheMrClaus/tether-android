package com.tether.app.ui.chat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Opens a markdown link the way the web's `<a target="_blank" rel="noopener noreferrer nofollow">`
 * does on a phone: OUTSIDE the app. PLAN §0.1 allows exactly one form for an external URL — a
 * Chrome Custom Tab, Android's native "open link" — and never a WebView.
 */
fun interface LinkOpener {
    fun open(context: Context, href: String, toolbarColor: Color)
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
        if (!isSafeHref(href)) return null // markdown.tsx SAFE_HREF: never javascript:/data:/intent:
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(href))
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
