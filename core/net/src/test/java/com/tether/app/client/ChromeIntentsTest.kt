package com.tether.app.client

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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

    @Test fun onlyContentAndFileDataAreRefused() {
        for (scheme in listOf("content", "file", "FILE", "Content")) {
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
        assertEquals("market://details?id=com.example.gh", market.dataString)
        assertTrue(market.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(emptyList<String>(), web)
        // A fallback that is not a web address is not one: the store again.
        val other = Phone(noApp)
        assertTrue(ChromeIntents.open(other, "intent://pr/1#Intent;scheme=gh;package=com.example.gh;S.browser_fallback_url=javascript%3Ax;end", newTask = false) { true })
        assertEquals("market://details?id=com.example.gh", other.started.single().dataString)
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
}
