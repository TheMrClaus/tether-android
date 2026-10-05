package com.tether.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T4.4: where a parsed link goes, for every sign-in and connection state. Pure JVM. */
class DeepLinkNavigatorTest {

    private val a = "https://a.example"
    private val b = "https://b.example"

    private fun ctx(
        signedIn: Boolean = true,
        server: String? = a,
        connected: Boolean = true,
        ids: Set<String> = setOf("s1", "s2"),
    ) = NavContext(signedIn, server, connected, ids)

    private fun tether(id: String) = DeepLinks.parse("tether://session/$id", null)
    private fun web(server: String, id: String) = DeepLinks.parse("$server/?session=$id", server)

    private val open = { id: String -> NavEffect.Open(id) }

    @Test
    fun aListedSessionOpensAtOnce() {
        val nav = DeepLinkNavigator()
        assertEquals(open("s1"), nav.offer(tether("s1"), ctx()))
        assertNull(nav.pendingSessionId)
        // Fulfilled once: later changes do not re-open it.
        assertNull(nav.step(ctx()))
    }

    /**
     * ta-coik.42 (web differs from the old app): signed in to the link's server, the link is handed
     * to the console at once, listed or not and connected or not; the console's pending target waits
     * for the list (dashboard.tsx 90fbb9f :292). The old app held it here until listed and dropped an
     * unlisted one with a notice.
     */
    @Test
    fun aSignedInLinkIsHandedOverBeforeTheSnapshot() {
        val nav = DeepLinkNavigator()
        assertEquals(open("s9"), nav.offer(tether("s9"), ctx(connected = false, ids = emptySet())))
        assertNull(nav.pendingSessionId)
    }

    @Test
    fun aStaleIdIsHandedOverWithNoNotice() {
        val nav = DeepLinkNavigator()
        assertEquals(open("gone"), nav.offer(tether("gone"), ctx()))
        assertNull(nav.pendingSessionId)
        assertNull(nav.step(ctx(ids = setOf("s1"))))
    }

    @Test
    fun rejectedLinksGetANoticeOnlyWhenSignedIn() {
        val nav = DeepLinkNavigator()
        assertEquals(NavEffect.Notice(DeepLinkNavigator.OTHER_SERVER), nav.offer(DeepLinks.parse("$b/?session=s1", a), ctx()))
        assertEquals(NavEffect.Notice(DeepLinkNavigator.CANNOT_OPEN), nav.offer(DeepLinks.parse("javascript:alert(1)", a), ctx()))
        assertEquals(NavEffect.Notice(DeepLinkNavigator.CANNOT_OPEN), nav.offer(DeepLinks.parse("tether://session/../x", a), ctx()))
        assertNull(nav.offer(DeepLinks.parse("$b/?session=s1", a), ctx(signedIn = false)))
        assertNull(nav.pendingSessionId)
    }

    @Test
    fun homeOpensTheAppAndLeavesAWaitingLinkAlone() {
        val nav = DeepLinkNavigator()
        assertNull(nav.offer(ParsedLink.Open(Destination.Home), ctx()))
        assertNull(nav.offer(tether("s9"), ctx(signedIn = false)))
        assertNull(nav.offer(ParsedLink.Open(Destination.Home), ctx(signedIn = false)))
        assertEquals("s9", nav.pendingSessionId)
    }

    @Test
    fun signedOutTheLinkWaitsAndOpensAfterSignInToTheSameServer() {
        val nav = DeepLinkNavigator()
        assertNull(nav.offer(tether("s1"), ctx(signedIn = false, server = a)))
        assertNull(nav.step(ctx(signedIn = false, server = a)))
        assertEquals("s1", nav.pendingSessionId)
        assertEquals(open("s1"), nav.step(ctx(signedIn = true, server = a)))
    }

    @Test
    fun aSignInToAnotherServerNeverOpensTheLink() {
        val nav = DeepLinkNavigator()
        assertNull(nav.offer(tether("s1"), ctx(signedIn = false, server = a)))
        // The other server happens to list the same id: still refused.
        assertEquals(NavEffect.Notice(DeepLinkNavigator.OTHER_SERVER), nav.step(ctx(signedIn = true, server = b)))
        assertNull(nav.step(ctx(signedIn = true, server = a)))
    }

    @Test
    fun anHttpLinkIsBoundToItsOwnOrigin() {
        val nav = DeepLinkNavigator()
        assertNull(nav.offer(web(a, "s1"), ctx(signedIn = false, server = a)))
        assertEquals(NavEffect.Notice(DeepLinkNavigator.OTHER_SERVER), nav.step(ctx(signedIn = true, server = b)))
    }

    @Test
    fun aServerSwitchWhileWaitingDropsTheLink() {
        val nav = DeepLinkNavigator()
        assertNull(nav.offer(tether("s9"), ctx(signedIn = false)))
        assertEquals(NavEffect.Notice(DeepLinkNavigator.OTHER_SERVER), nav.step(ctx(server = b, connected = false, ids = setOf("s9"))))
    }

    @Test
    fun aLinkThatNamesNoServerIsNotFollowedAfterAFirstSignIn() {
        val nav = DeepLinkNavigator()
        assertNull(nav.offer(tether("s1"), ctx(signedIn = false, server = null)))
        assertEquals(NavEffect.Notice(DeepLinkNavigator.SIGNED_IN_OPEN_AGAIN), nav.step(ctx(signedIn = true, server = a)))
        assertNull(nav.pendingSessionId)
    }

    @Test
    fun anExplicitSelectionRetiresAWaitingLink() {
        val nav = DeepLinkNavigator()
        assertNull(nav.offer(tether("s9"), ctx(signedIn = false)))
        nav.onUserSelection()
        assertNull(nav.step(ctx(ids = setOf("s9"))))
    }

    @Test
    fun theNewestLinkWins() {
        val nav = DeepLinkNavigator()
        assertNull(nav.offer(tether("s8"), ctx(signedIn = false)))
        assertNull(nav.offer(tether("s9"), ctx(signedIn = false)))
        assertEquals(open("s9"), nav.step(ctx(ids = setOf("s8", "s9"))))
    }

    @Test
    fun noLinkIsNoEffect() {
        val nav = DeepLinkNavigator()
        assertNull(nav.offer(null, ctx()))
        assertNull(nav.step(ctx()))
    }
}
