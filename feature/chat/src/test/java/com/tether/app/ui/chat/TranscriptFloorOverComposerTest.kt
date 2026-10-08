package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-4vun + ta-lz64 (W21, ruling R2): the transcript's box is never shorter than twice its vertical content padding (64 dp
 * on the desktop, 48 dp on the phone: the web's `.chat-scroll` cannot be shorter than its padding), so where the region
 * above the composer shrinks below that it overflows downward over the composer's static top strip, clipped (paint and
 * touch) at the well's top edge. The web at tether 29537e0 as served, phone layout, 740 x 320: scroller 48 over a 43.81
 * region; Allow 117.41-161.41 and hit-testable. Hosted like MidBandSmallWindowTest (144 dp of spacer above, the topbar and
 * header). No height or orientation term anywhere.
 */
abstract class TranscriptFloorBase(private val windowW: Int, private val windowH: Int, private val phone: Boolean) {
    @get:Rule val rule = createComposeRule()

    private val d get() = rule.density.density
    private fun dp(px: Float) = px / d
    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private lateinit var client: ChatTestClient

    private val longReply = "A reply that is long enough to wrap across the whole transcript column so that its right edge is the column's. ".repeat(6)
    private val idle get() = ChatFixtures.fold(*ChatFixtures.turn("t1", "Summarize the README in one line.", longReply, ChatFixtures.T_IDLE))

    private fun host(folded: ChatFixtures.Folded) {
        val session = chatSession("s1", historyId = null)
        client = ChatTestClient().also {
            it.show(session, folded)
            it.sessionControls.value = mapOf("s1" to SessionControlFixtures.claudeIdleControls.copy(sessionId = "s1"))
        }
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(144.dp))
                    Box(Modifier.fillMaxSize().testTag("mid-band-chat")) {
                        ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private val floor get() = if (phone) 48.0 else 64.0

    /** region = the transcript's top to the composer's top; strip = the composer's top to the well's top; band = min(box, region + strip). */
    private class Geometry(val box: Double, val region: Double, val strip: Double) { val band get() = minOf(box, region + strip) }

    private fun geometry(): Geometry {
        val t = bounds("chat-transcript"); val c = bounds(CHAT_COMPOSER_TAG); val w = bounds(CHAT_COMPOSER_WELL_TAG)
        return Geometry(dp(t.height).toDouble(), dp(c.top - t.top).toDouble(), dp(w.top - c.top).toDouble())
    }

    @Test fun withAnApprovalTheBoxIsTheFloorOverTheStripAndAllowIsTappableInTheBand() {
        host(ApprovalFixtures.write)
        val g = geometry()
        println("W21-RECORD floor w${windowW}h$windowH approval: box=${g.box} region=${g.region} strip=${g.strip} band=${g.band}")
        assertEquals("the box is the floor (region ${g.region})", floor, g.box, 1.0)
        assertTrue("the region is below the floor here: ${g.region}", g.region < floor)
        if (!phone) {
            assertEquals("visible band = min(box, region + strip) = 42", 42.0, g.band, 2.0)
            assertEquals("region is 0 at this window", 0.0, g.region, 1.0)
        }
        val composer = bounds(CHAT_COMPOSER_TAG)
        if (!phone) {
            for ((name, r) in listOf(
                "Attach" to rule.onNodeWithContentDescriptionCompat("Add attachment"),
                "Queue" to rule.onNodeWithContentDescriptionCompat("Queue message"),
                "Interrupt" to bounds(INTERRUPT_KEY_TAG),
                "Browser" to bounds(BrowserPaneTags.Toggle),
            )) {
                assertTrue("$name bottom ${dp(r.bottom)} inside the $windowH dp window", dp(r.bottom) <= windowH.toFloat() && dp(r.right) <= windowW.toFloat())
                assertEquals("$name is a full 44 tall", 44.0, dp(r.height).toDouble(), 0.5)
            }
        } else {
            println("W21-RECORD floor phone composer: top=${dp(composer.top)} bottom=${dp(composer.bottom)} of $windowH")
        }
        // Bring Allow into the band: its top 4 dp under the box's top, as the web's scrolled reader sees it (the card's keys at 150-184 under a 144 top).
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("approval-allow"))
        rule.waitForIdle()
        val box = bounds("chat-transcript")
        val allow0 = bounds("approval-allow")
        val dy = allow0.top - (box.top + 4f * d)
        rule.onNodeWithTag("chat-transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
        rule.waitForIdle()
        val allow = bounds("approval-allow")
        val wellTop = bounds(CHAT_COMPOSER_WELL_TAG).top
        println("W21-RECORD floor w${windowW}h$windowH allow: top=${dp(allow.top)} bottom=${dp(allow.bottom)} wellTop=${dp(wellTop)} boxTop=${dp(box.top)} visibleTop=${dp(minOf(allow.bottom, wellTop) - allow.top)}")
        // A touch in the well (below the band) does not reach Allow; one in the band does.
        val x = allow.left + 12f * d
        rule.onRoot().performTouchInput { click(Offset(x, wellTop + 4f * d)) }
        rule.waitForIdle()
        assertTrue("a touch below the band's end reaches nothing: ${client.consentCalls}", client.consentCalls.isEmpty())
        rule.onRoot().performTouchInput { click(Offset(x, box.top + 10f * d)) }
        rule.waitForIdle()
        assertEquals("a touch in the band reaches Allow", 1, client.consentCalls.count { it.startsWith("approval:s1:req-w:") })
        assertTrue("composer untouched: ${composer.top}", composer.top >= 0f)
    }

    @Test fun idleTheBandIsTheRegionPlusTheDecksTopPadding() {
        host(idle)
        val g = geometry()
        println("W21-RECORD floor w${windowW}h$windowH idle: box=${g.box} region=${g.region} strip=${g.strip} band=${g.band}")
        assertEquals("the box is max(region, floor)", maxOf(g.region, floor), g.box, 1.0)
        assertEquals("strip is the deck's top padding", if (phone) 10.0 else 16.0, g.strip, 2.0)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionCompat(text: String): Rect =
    onNodeWithContentDescription(text).fetchSemanticsNode().boundsInRoot

@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w780dp-h360dp-420dpi")
class TranscriptFloor780x360Test : TranscriptFloorBase(780, 360, phone = false)

@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w914dp-h359dp-420dpi")
class TranscriptFloor914x359Test : TranscriptFloorBase(914, 359, phone = false)

/** The phone, against the PRIMARY capture (served phone layout, 740 x 320): the box is 48. */
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w740dp-h320dp-420dpi")
class TranscriptFloor740x320PhoneTest : TranscriptFloorBase(740, 320, phone = true)
