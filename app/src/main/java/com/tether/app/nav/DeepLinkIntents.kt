package com.tether.app.nav

import android.content.Intent
import com.tether.app.push.PushDeepLink

/**
 * What an intent that reached MainActivity asks for, or null when it asks for nothing (the
 * launcher, Recents). MainActivity is exported, so every intent is untrusted, and each channel
 * reads only its own field:
 *
 * - A notification tap ([PushDeepLink.parse]) opens the app: [Destination.Home]. It never names a
 *   session. The server's FCM payload is id-free by design (`lib/push-notifications.mjs`: FCM data
 *   is readable by Google, so only the end-to-end encrypted Web Push path carries `webUrl`), and
 *   any app can send these actions with any extras (T12.1 security review, H1). Its data URI is
 *   not read either.
 * - `ACTION_VIEW` reads the data URI only, through [DeepLinks.parse]. Extras are never read.
 */
object DeepLinkIntents {
    fun parse(intent: Intent?, pairedBaseUrl: String?): ParsedLink? {
        intent ?: return null
        if (PushDeepLink.parse(intent) != null) return ParsedLink.Open(Destination.Home)
        if (intent.action != Intent.ACTION_VIEW) return null
        return DeepLinks.parse(intent.dataString, pairedBaseUrl)
    }
}
