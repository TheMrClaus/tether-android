package com.tether.app.ui.localnet

import android.Manifest
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import com.tether.app.net.AndroidLocalNetworkAccess
import com.tether.app.ui.theme.TetherTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The permission UI's states, rendered for real (Compose in a Robolectric
 * activity, asserted via the semantics tree TalkBack reads).
 *
 * Robolectric runs at SDK 34 here, not 37: the project's JDK is 17 and
 * Robolectric's newer SDK sandboxes need Java 21 (same pin as
 * TetherFcmServiceTest). The composables don't depend on the API level. What
 * does depend on it (enforced / granted) is asserted below for API 34, where
 * the flow must stay inert. The API 37 path needs a device (see the T0.6 notes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalNetworkUiTest {

    private val roots = mutableListOf<ViewRootForTest>()

    @Before
    fun collectRoots() {
        // Every Compose root (the activity's and the dialog window's) reports here.
        ViewRootForTest.onViewCreatedCallback = { roots += it }
    }

    @After
    fun reset() {
        ViewRootForTest.onViewCreatedCallback = null
    }

    private fun render(content: @Composable () -> Unit): List<SemanticsNode> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { TetherTheme { content() } }
        shadowOf(Looper.getMainLooper()).idle()
        density = activity.resources.displayMetrics.density
        return roots.flatMap { flatten(it.semanticsOwner.unmergedRootSemanticsNode) }
    }

    private var density = 1f

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun List<SemanticsNode>.texts() =
        flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun List<SemanticsNode>.clickableLabelled(label: String): SemanticsNode? = firstOrNull { n ->
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
            n.config.getOrNull(SemanticsActions.OnClick) != null
    }

    @Test
    fun `denied notice - icon plus words, a 44dp Allow key with a TalkBack label, announced politely`() {
        var allowed = 0
        val nodes = render { LocalNetworkNotice(canRequest = true, onAllow = { allowed++ }) }

        val texts = nodes.texts()
        assertTrue(texts.toString(), LocalNetworkCopy.NOTICE_TITLE in texts)
        assertTrue(texts.toString(), LocalNetworkCopy.NOTICE_BODY in texts)
        assertTrue(texts.toString(), LocalNetworkCopy.ALLOW.uppercase() in texts)
        assertTrue(
            "notice is a polite live region",
            nodes.any { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite },
        )

        val allow = nodes.clickableLabelled(LocalNetworkCopy.ALLOW_A11Y)
        assertNotNull("Allow key carries its TalkBack label", allow)
        assertTrue("Allow key is at least 44dp tall", allow!!.size.height >= (44 * density).toInt())
        allow.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, allowed)
    }

    @Test
    fun `denied for good - the key says Open settings, and the copy says where`() {
        val nodes = render { LocalNetworkNotice(canRequest = false, onAllow = {}) }
        val texts = nodes.texts()
        assertTrue(texts.toString(), LocalNetworkCopy.NOTICE_BODY_SETTINGS in texts)
        assertTrue(texts.toString(), LocalNetworkCopy.OPEN_SETTINGS.uppercase() in texts)
        assertNotNull(nodes.clickableLabelled(LocalNetworkCopy.OPEN_SETTINGS_A11Y))
    }

    @Test
    fun `explanation - says why, then Continue or Not now`() {
        var continued = 0
        var dismissed = 0
        val nodes = render { LocalNetworkExplainDialog(onContinue = { continued++ }, onNotNow = { dismissed++ }) }
        val texts = nodes.texts()
        assertTrue(texts.toString(), LocalNetworkCopy.EXPLAIN_TITLE in texts)
        assertTrue(texts.toString(), LocalNetworkCopy.EXPLAIN_BODY in texts)

        fun click(legend: String) = nodes.first { n ->
            n.config.getOrNull(SemanticsActions.OnClick) != null &&
                flatten(n).texts().contains(legend.uppercase())
        }.config[SemanticsActions.OnClick].action!!.invoke()
        click(LocalNetworkCopy.CONTINUE)
        click(LocalNetworkCopy.NOT_NOW)
        assertEquals(1, continued)
        assertEquals(1, dismissed)
    }

    @Test
    fun `below API 37 the flow is inert - never restricted, even without the grant`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(Manifest.permission.ACCESS_LOCAL_NETWORK, AndroidLocalNetworkAccess.PERMISSION)
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", AndroidLocalNetworkAccess.PERMISSION)
        assertFalse(AndroidLocalNetworkAccess.enforced(context))
        assertTrue(AndroidLocalNetworkAccess.granted(context))
        assertFalse(AndroidLocalNetworkAccess(context).isRestricted())
    }
}
