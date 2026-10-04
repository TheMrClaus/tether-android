package com.tether.app.client

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-coik.18 r2: the shared `intent:` rule matches Chrome on Android (Chromium
 * ExternalNavigationHandler: sanitizeQueryIntentActivitiesIntent, its fallback URL and store
 * redirect) and is never stricter: Chrome's allowed flags kept, grants dropped, no selector, no
 * component, only `content:` / `file:` data refused.
 */
@RunWith(RobolectricTestRunner::class)
class ChromeIntentsTest {
    /** Records every intent started; those [refuses] matches throw as when no app takes them. */
    private class Phone(private val refuses: (Intent) -> Boolean) : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        val tried = mutableListOf<Intent>()
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            tried += intent
            if (refuses(intent)) throw ActivityNotFoundException(intent.toString())
            started += intent
        }
    }

    private val noApp: (Intent) -> Boolean = { it.scheme != "market" }

    @Test fun chromesAllowedFlagsAreKeptAndGrantsDropped() {
        // SINGLE_TOP | CLEAR_TOP, as Chrome keeps them.
        assertEquals(0x24000000, ChromeIntents.parse("intent://x#Intent;scheme=gh;launchFlags=0x24000000;end")!!.flags)
        // Read, write and persistable URI grants (0x43) never go out; the allowed flags beside them do.
        assertEquals(0x24000000, ChromeIntents.parse("intent://x#Intent;scheme=gh;launchFlags=0x24000043;end")!!.flags)
        // CLEAR_TASK is not one of Chrome's.
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, ChromeIntents.parse("intent://x#Intent;scheme=gh;launchFlags=0x10008000;end")!!.flags)
        // The mask itself drops grants, whatever the intent's source (parseUri already drops a link's).
        val granted = Intent(Intent.ACTION_VIEW).addFlags(0x43 or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, ChromeIntents.sanitize(granted).flags)
        // Chrome's nine allowed flags, each by name, and the value they make (pinned, not compared with itself).
        val chromes = listOf(
            Intent.FLAG_EXCLUDE_STOPPED_PACKAGES, Intent.FLAG_ACTIVITY_CLEAR_TOP, Intent.FLAG_ACTIVITY_SINGLE_TOP,
            Intent.FLAG_ACTIVITY_MATCH_EXTERNAL, Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            Intent.FLAG_ACTIVITY_NEW_DOCUMENT, Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS, Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT,
        )
        assertEquals(chromes.fold(0) { acc, f -> acc or f }, ChromeIntents.ALLOWED_INTENT_FLAGS)
        assertEquals(0x3C083810, ChromeIntents.ALLOWED_INTENT_FLAGS)
        for (flag in chromes) {
            assertEquals(Integer.toHexString(flag), flag, ChromeIntents.parse("intent://x#Intent;scheme=gh;launchFlags=0x${Integer.toHexString(flag)};end")!!.flags)
        }
        // Every one of Chrome's allowed flags survives.
        val all = ChromeIntents.parse("intent://x#Intent;scheme=gh;launchFlags=0x${Integer.toHexString(ChromeIntents.ALLOWED_INTENT_FLAGS)};end")!!
        assertEquals(ChromeIntents.ALLOWED_INTENT_FLAGS, all.flags)
    }

    @Test fun theSelectorAndComponentAreRemoved() {
        // A selector would carry the BROWSABLE match while the main intent names something else (crbug 1254422).
        val intent = ChromeIntents.parse(
            "intent:#Intent;action=OPEN_TETHER;component=com.example.other/.Secret;S.x=y;SEL;action=android.intent.action.VIEW;scheme=tether;component=com.example.other/.Sel;end",
        )!!
        assertNull("no selector", intent.selector)
        assertNull("no component", intent.component)
        assertEquals("OPEN_TETHER", intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test fun onlyChromesRefusedDataSchemesAreRefused() {
        for (scheme in listOf("content", "file", "FILE", "Content", "about", "chrome", "chrome-native", "devtools", "fido", "ABOUT", "Chrome-Native", "FIDO")) {
            assertNull(scheme, ChromeIntents.parse("intent://x/y#Intent;scheme=$scheme;end"))
        }
        for (scheme in listOf("data", "blob", "filesystem", "gh", "https")) {
            assertEquals(scheme, "$scheme://x/y", ChromeIntents.parse("intent://x/y#Intent;scheme=$scheme;end")?.dataString)
        }
        // A link that does not parse (any exception parseUri throws) opens nothing.
        assertNull(ChromeIntents.parse("intent://x#Intent;launchFlags=zz;end"))
    }

    @Test fun theAppThatTakesItGetsTheSanitisedIntent() {
        val phone = Phone { false }
        val web = mutableListOf<String>()
        assertTrue(ChromeIntents.open(phone, "intent://pr/1#Intent;scheme=gh;package=com.example.gh;end", newTask = false) { web += it; true })
        val started = phone.started.single()
        assertEquals("gh://pr/1", started.dataString)
        assertEquals("com.example.gh", started.`package`)
        assertEquals(emptyList<String>(), web)
    }

    @Test fun noAppTakesItOpensItsWebFallbackFirst() {
        val phone = Phone(noApp)
        val web = mutableListOf<String>()
        val link = "intent://pr/1#Intent;scheme=gh;package=com.example.gh;S.browser_fallback_url=https%3A%2F%2Fexample.test%2Fpr%2F1;end"
        assertTrue(ChromeIntents.open(phone, link, newTask = false) { web += it; true })
        assertEquals(listOf("https://example.test/pr/1"), web)
        assertEquals("no store when the link names a fallback", 1, phone.tried.size)
    }

    @Test fun noAppAndNoFallbackOpensThePackagesStorePage() {
        val phone = Phone(noApp)
        val web = mutableListOf<String>()
        assertTrue(ChromeIntents.open(phone, "intent://pr/1#Intent;scheme=gh;package=com.example.gh;end", newTask = false) { web += it; true })
        val market = phone.started.single()
        assertEquals(Intent.ACTION_VIEW, market.action)
        // Chrome's: the Play Store app, the link's referrer or else the opener's own package.
        assertEquals("market://details?id=com.example.gh&referrer=" + ApplicationProvider.getApplicationContext<Context>().packageName, market.dataString)
        assertEquals(ChromeIntents.PLAY_STORE_PACKAGE, market.`package`)
        assertTrue(market.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(emptyList<String>(), web)
        // A fallback that is not a web address is not one: the store again.
        val other = Phone(noApp)
        assertTrue(ChromeIntents.open(other, "intent://pr/1#Intent;scheme=gh;package=com.example.gh;S.browser_fallback_url=javascript%3Ax;end", newTask = false) { true })
        assertEquals("market://details?id=com.example.gh&referrer=" + ApplicationProvider.getApplicationContext<Context>().packageName, other.started.single().dataString)
    }

    @Test fun noStoreAppOpensThePlayWebPage() {
        val phone = Phone { true }
        val web = mutableListOf<String>()
        assertTrue(ChromeIntents.open(phone, "intent://pr/1#Intent;scheme=gh;package=com.example.gh;end", newTask = false) { web += it; true })
        assertEquals(listOf("https://play.google.com/store/apps/details?id=com.example.gh"), web)
    }

    @Test fun noAppNoFallbackNoPackageOpensNothing() {
        val phone = Phone { true }
        assertFalse(ChromeIntents.open(phone, "intent://pr/1#Intent;scheme=gh;end", newTask = false) { error("no web fallback") })
        assertEquals(1, phone.tried.size)
        assertFalse(ChromeIntents.open(phone, "intent://x#Intent;launchFlags=zz;end", newTask = false) { error("unparsed") })
        assertEquals("an unparsed link starts nothing", 1, phone.tried.size)
    }

    @Test fun aRefusingPlatformNeverCrashes() {
        val throwing = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) = throw SecurityException("not exported")
        }
        assertFalse(ChromeIntents.start(throwing, ChromeIntents.view("gh://pr/1")))
        assertNotNull(ChromeIntents.view("gh://pr/1").categories)
    }

    /** r3 F1/F6: one browser-only set for a page's link, shared by the login link and the inspector. */
    @Test fun theBrowserOnlySetIsChromesAndTheLoginLinkUsesIt() {
        assertEquals(
            setOf("file", "content", "data", "blob", "filesystem", "about", "chrome", "chrome-native", "devtools", "fido"),
            ChromeIntents.BROWSER_ONLY_SCHEMES,
        )
        for (raw in listOf("blob:https://x.test/1", "filesystem:https://x.test/t/a", "about:blank", "ABOUT:blank", "chrome://settings", "chrome-native://newtab", "devtools://devtools/x", "FIDO:/x", "data:,x", "file:///x", "content://x/y", "javascript:x")) {
            assertNull(raw, ClaudeLoginLink.parse(raw))
        }
        assertNotNull(ClaudeLoginLink.parse("market://details?id=x"))
    }

    /** r3 F2: Chrome's resolvesToNonExportedActivity: a link that would reach this app's own non-exported activity starts nothing. */
    /** Registers [component] as a BROWSABLE VIEW handler of `tether-inner:` ([default]: with CATEGORY_DEFAULT). */
    private fun register(component: android.content.ComponentName, exported: Boolean, default: Boolean = true): android.content.pm.ActivityInfo {
        val pm = org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<Context>().packageManager)
        val info = pm.addActivityIfNotPresent(component)
        info.exported = exported
        pm.addOrUpdateActivity(info)
        pm.addIntentFilterForActivity(
            component,
            android.content.IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                if (default) addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("tether-inner")
            },
        )
        return info
    }

    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private val fallbackPart = "S.browser_fallback_url=https%3A%2F%2Fexample.test%2Ffb;"

    /**
     * r3 F2 / r4: a link that would reach this app's own non-exported activity starts nothing; like
     * every refusal once the link parses, it opens its web fallback (Chrome's handleFallbackUrl), never the store.
     */
    @Test fun aLinkToOurOwnNonExportedActivityOpensOnlyItsWebFallback() {
        val info = register(android.content.ComponentName(app.packageName, "com.tether.app.Inner"), exported = false)
        val link = "intent://x#Intent;scheme=tether-inner;$fallbackPart" + "end"
        assertTrue(ChromeIntents.resolvesToNonExportedActivity(app, ChromeIntents.parse(link)!!))
        val phone = Phone { false }
        val web = mutableListOf<String>()
        assertTrue(ChromeIntents.open(phone, link, newTask = false) { web += it; true })
        assertEquals(listOf("https://example.test/fb"), web)
        assertEquals(emptyList<Intent>(), phone.tried)
        // Without a fallback: nothing, and never the store of the package it names.
        assertFalse(ChromeIntents.open(phone, "intent://x#Intent;scheme=tether-inner;end", newTask = false) { error("no fallback") })
        assertEquals(emptyList<Intent>(), phone.tried)
        // A plain link to it (no intent:) is refused too, and has no fallback.
        assertFalse(ChromeIntents.openView(phone, "tether-inner://x", newTask = false))
        assertEquals(emptyList<Intent>(), phone.tried)
        // Exported, it is any app's: it goes out.
        info.exported = true
        org.robolectric.Shadows.shadowOf(app.packageManager).addOrUpdateActivity(info)
        assertFalse(ChromeIntents.resolvesToNonExportedActivity(app, ChromeIntents.parse(link)!!))
        assertTrue(ChromeIntents.open(phone, link, newTask = false) { error("taken by the app") })
        assertEquals("tether-inner://x", phone.started.single().dataString)
        assertTrue(ChromeIntents.openView(phone, "tether-inner://x", newTask = false))
    }

    /** r4: the package's store is never the answer to a refusal; only the web fallback is. */
    @Test fun aRefusedLinkThatNamesAPackageNeverGoesToTheStore() {
        register(android.content.ComponentName(app.packageName, "com.tether.app.Inner"), exported = false)
        val phone = Phone { false }
        assertFalse(ChromeIntents.open(phone, "intent://x#Intent;scheme=tether-inner;package=${app.packageName};end", newTask = false) { error("no web") })
        assertFalse(ChromeIntents.open(phone, "intent://x#Intent;scheme=content;package=com.example.gh;end", newTask = false) { error("no web") })
        assertEquals(emptyList<Intent>(), phone.tried)
    }

    /** r4 (test gap): another app's non-exported activity is that app's business: it never blocks the link. */
    @Test fun anotherAppsNonExportedActivityDoesNotBlockTheLink() {
        register(android.content.ComponentName("com.example.other", "com.example.other.Inner"), exported = false)
        val intent = ChromeIntents.parse("intent://x#Intent;scheme=tether-inner;end")!!
        assertEquals(1, app.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).size)
        assertFalse(ChromeIntents.resolvesToNonExportedActivity(app, intent))
        val phone = Phone { false }
        assertTrue(ChromeIntents.open(phone, "intent://x#Intent;scheme=tether-inner;end", newTask = false) { error("taken") })
        assertTrue(ChromeIntents.openView(phone, "tether-inner://x", newTask = false))
        assertEquals(2, phone.started.size)
    }

    /** r4 (ta-qap9): queried as Chrome, MATCH_DEFAULT_ONLY: an own activity without CATEGORY_DEFAULT is not a match. */
    @Test fun theNonExportedQueryMatchesDefaultActivitiesOnly() {
        register(android.content.ComponentName(app.packageName, "com.tether.app.Inner"), exported = false, default = false)
        assertFalse(ChromeIntents.resolvesToNonExportedActivity(app, ChromeIntents.parse("intent://x#Intent;scheme=tether-inner;end")!!))
    }

    /** r4 (ta-qap9): a query that throws fails closed: nothing starts, the web fallback opens. */
    @Test fun aQueryThatThrowsStartsNothingAndOpensTheFallback() {
        val tried = mutableListOf<Intent>()
        val phone = object : ContextWrapper(app) {
            override fun getPackageManager(): android.content.pm.PackageManager = throw IllegalStateException("package manager died")
            override fun startActivity(intent: Intent) { tried += intent }
        }
        val web = mutableListOf<String>()
        assertTrue(ChromeIntents.open(phone, "intent://x#Intent;scheme=gh;package=com.example.gh;$fallbackPart" + "end", newTask = false) { web += it; true })
        assertEquals(listOf("https://example.test/fb"), web)
        assertFalse(ChromeIntents.open(phone, "intent://x#Intent;scheme=gh;package=com.example.gh;end", newTask = false) { error("no web") })
        assertFalse(ChromeIntents.openView(phone, "gh://x", newTask = false))
        assertEquals(emptyList<Intent>(), tried)
    }

    /** r4: a link whose data Chrome refuses opens its web fallback, per refused scheme; without one, nothing. */
    @Test fun aRefusedDataLinkOpensOnlyItsWebFallback() {
        for (scheme in ChromeIntents.REFUSED_DATA_SCHEMES) {
            val phone = Phone { false }
            val web = mutableListOf<String>()
            assertTrue(scheme, ChromeIntents.open(phone, "intent://x/y#Intent;scheme=$scheme;package=com.example.gh;$fallbackPart" + "end", newTask = false) { web += it; true })
            assertEquals(scheme, listOf("https://example.test/fb"), web)
            assertFalse(scheme, ChromeIntents.open(phone, "intent://x/y#Intent;scheme=$scheme;package=com.example.gh;end", newTask = false) { error("no web") })
            assertEquals(scheme, emptyList<Intent>(), phone.tried)
        }
        assertEquals(setOf("content", "file", "about", "chrome", "chrome-native", "devtools", "fido"), ChromeIntents.REFUSED_DATA_SCHEMES)
    }

    /** r3 F3: the fallback address is read, then removed: the app that takes the link never receives it. */
    @Test fun theFallbackUrlIsRemovedBeforeTheLinkGoesOut() {
        val phone = Phone { false }
        assertTrue(ChromeIntents.open(phone, "intent://x#Intent;scheme=gh;S.browser_fallback_url=https%3A%2F%2Fexample.test%2F;S.k=v;end", newTask = false) { true })
        val started = phone.started.single()
        assertNull(started.getStringExtra(ChromeIntents.EXTRA_BROWSER_FALLBACK_URL))
        assertEquals("other extras stay", "v", started.getStringExtra("k"))
    }

    /** r3 F4: the link's own market_referrer is passed on as Chrome does. */
    @Test fun theLinksMarketReferrerIsPassedOn() {
        val phone = Phone(noApp)
        assertTrue(ChromeIntents.open(phone, "intent://x#Intent;scheme=gh;package=com.example.gh;S.market_referrer=utm_source%3Dpr;end", newTask = false) { true })
        val market = phone.started.single()
        assertEquals("utm_source=pr", Uri.parse(market.dataString).getQueryParameter("referrer"))
        assertEquals("com.example.gh", Uri.parse(market.dataString).getQueryParameter("id"))
        assertEquals(ChromeIntents.PLAY_STORE_PACKAGE, market.`package`)
        // r4: passed on as parsed (parseUri decodes once; it is not decoded again), and an empty one stays empty.
        fun referrer(extra: String): String? {
            val p = Phone(noApp)
            assertTrue(ChromeIntents.open(p, "intent://x#Intent;scheme=gh;package=com.example.gh;$extra" + "end", newTask = false) { true })
            return Uri.parse(p.started.single().dataString).getQueryParameter("referrer")
        }
        assertEquals("a%3Db", referrer("S.market_referrer=a%253Db;"))
        assertEquals("", referrer("S.market_referrer=;"))
        assertEquals(app.packageName, referrer(""))
    }

    /** r3 F5: the fallback is read with the URL parser's clean-up and passed on in its canonical form. */
    @Test fun theFallbackUrlIsReadAsTheUrlParserReadsIt() {
        assertEquals("https://example.test/a%20b", ChromeIntents.webFallback(" \u0001https://EXAMPLE.test/a b\n"))
        assertEquals("https://example.test/pr", ChromeIntents.webFallback("HTTPS://exam\tple.test/pr"))
        assertEquals("http://example.test/", ChromeIntents.webFallback("http://example.test"))
        for (raw in listOf(null, "", "javascript:alert(1)", "ftp://x.test/", "/relative", "https://")) assertNull(raw, ChromeIntents.webFallback(raw))
        val web = mutableListOf<String>()
        val phone = Phone { true }
        assertTrue(ChromeIntents.open(phone, "intent://x#Intent;scheme=gh;S.browser_fallback_url=%20HTTPS%3A%2F%2FExample.test%2Fpr%0A;end", newTask = false) { web += it; true })
        assertEquals(listOf("https://example.test/pr"), web)
    }

    /** A context whose package manager query throws [thrown]; it records what is started. */
    private class Throwing(base: Context, private val thrown: () -> Throwable) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        override fun getPackageManager(): android.content.pm.PackageManager = throw thrown()
        override fun startActivity(intent: Intent) { started += intent }
    }

    /**
     * r5 (ta-kn42 P3-1): Chromium's PackageManagerUtils reads a TransactionTooLargeException (wrapped)
     * as an empty result: the parsed intent never starts, and the link takes its unresolvable path,
     * here the store page of the package it names.
     */
    @Test fun aTooLargeQueryIsAnEmptyResultSoTheStoreOpens() {
        val phone = Throwing(app) { RuntimeException(android.os.TransactionTooLargeException()) }
        assertTrue(ChromeIntents.open(phone, "intent://pr/1#Intent;scheme=gh;package=com.example.gh;end", newTask = false) { error("store first") })
        assertEquals("market://details?id=com.example.gh&referrer=" + app.packageName, phone.started.single().dataString)
        assertEquals(ChromeIntents.PLAY_STORE_PACKAGE, phone.started.single().`package`)
    }

    @Test fun aNullPointerQueryIsAnEmptyResultSoTheStoreOpens() {
        val phone = Throwing(app) { NullPointerException("pm") }
        assertTrue(ChromeIntents.open(phone, "intent://pr/1#Intent;scheme=gh;package=com.example.gh;end", newTask = false) { error("store first") })
        assertEquals("market://details?id=com.example.gh&referrer=" + app.packageName, phone.started.single().dataString)
        // With a fallback, the fallback (as for any unresolvable link); a plain link starts nothing.
        val web = mutableListOf<String>()
        assertTrue(ChromeIntents.open(phone, "intent://pr/1#Intent;scheme=gh;package=com.example.gh;$fallbackPart" + "end", newTask = false) { web += it; true })
        assertEquals(listOf("https://example.test/fb"), web)
        assertFalse(ChromeIntents.openView(phone, "gh://x", newTask = false))
        assertEquals(1, phone.started.size)
    }

    /** r5: any other throw still fails closed: nothing starts, never the store, only the web fallback. */
    @Test fun anotherThrowStillStartsNothingAndNeverTheStore() {
        val phone = Throwing(app) { IllegalStateException("died") }
        assertFalse(ChromeIntents.open(phone, "intent://pr/1#Intent;scheme=gh;package=com.example.gh;end", newTask = false) { error("no web") })
        assertFalse(ChromeIntents.open(Throwing(app) { RuntimeException(android.os.RemoteException()) }, "intent://pr/1#Intent;scheme=gh;package=com.example.gh;end", newTask = false) { error("no web") })
        assertEquals(emptyList<Intent>(), phone.started)
    }

    /** r5 (P3-3): parse (test/inspection only) drops the fallback extra, as open does. */
    @Test fun parseDropsTheFallbackExtra() {
        val intent = ChromeIntents.parse("intent://x#Intent;scheme=gh;$fallbackPart" + "S.k=v;end")!!
        assertNull(intent.getStringExtra(ChromeIntents.EXTRA_BROWSER_FALLBACK_URL))
        assertEquals("v", intent.getStringExtra("k"))
    }
}
