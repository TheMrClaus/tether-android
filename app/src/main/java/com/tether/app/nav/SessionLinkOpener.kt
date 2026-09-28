package com.tether.app.nav

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.tether.app.ui.chat.LinkOpener

/**
 * A markdown link in a chat that names a session on the paired server (`https://<paired>/?session=
 * <id>`) opens that session in the app, through the same navigator as every other link. The web
 * opens it in a new tab of the same console, which lands on the same session; a Custom Tab here
 * would need its own browser sign-in instead.
 *
 * Every other link, including the paired server's other pages and any other origin, goes to
 * [delegate] (the Custom Tab) exactly as before: a link that is not a session link is not an
 * error, so it gets no notice.
 */
class SessionLinkOpener(
    private val delegate: LinkOpener,
    private val pairedBaseUrl: () -> String?,
    private val openInApp: (ParsedLink.Open) -> Unit,
) : LinkOpener {
    override fun open(context: Context, href: String, toolbarColor: Color) {
        val parsed = DeepLinks.parse(href, pairedBaseUrl())
        if (parsed is ParsedLink.Open && parsed.origin != null && parsed.destination is Destination.Session) {
            openInApp(parsed)
            return
        }
        delegate.open(context, href, toolbarColor)
    }
}
