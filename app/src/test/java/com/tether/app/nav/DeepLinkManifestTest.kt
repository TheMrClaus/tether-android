package com.tether.app.nav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tether.app.MainActivity
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * T4.4: the exported surface stays minimal. MainActivity is the only activity with filters in the
 * release manifest, its filters are exactly the launcher, the FCM click action and
 * `tether://session`, nothing claims http(s) (so no autoVerify), and it is `singleTop`: a launcher
 * relaunch must keep whatever was opened above it, which `singleTask` (or clearTaskOnLaunch) clears.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinkManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun manifest(path: String): Element {
        val file = File(path)
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String): String = getAttributeNS(androidNs, name)

    /** Each filter as a sorted "action|category|scheme://host" signature. */
    private fun Element.filterSignatures(): Set<String> = children("intent-filter").map { filter ->
        val parts = filter.children("action").map { "a:" + it.attr("name") } +
            filter.children("category").map { "c:" + it.attr("name") } +
            filter.children("data").map { "d:" + it.attr("scheme") + "://" + it.attr("host") + it.attr("path") + it.attr("pathPrefix") + it.attr("pathPattern") }
        parts.sorted().joinToString("|")
    }.toSet()

    @Test
    fun mainActivityHasExactlyThreeFilters() {
        val activities = manifest("src/main/AndroidManifest.xml").children("activity")
        val main = activities.single { it.attr("name") == ".MainActivity" }
        assertEquals("true", main.attr("exported"))
        assertEquals("singleTop", main.attr("launchMode"))
        // Nothing that clears the task above the root on a relaunch.
        for (attr in listOf("clearTaskOnLaunch", "finishOnTaskLaunch", "taskAffinity", "documentLaunchMode", "allowTaskReparenting")) {
            assertEquals(attr, "", main.attr(attr))
        }
        assertEquals(
            setOf(
                "a:android.intent.action.MAIN|c:android.intent.category.LAUNCHER",
                "a:OPEN_TETHER|c:android.intent.category.DEFAULT",
                "a:android.intent.action.VIEW|c:android.intent.category.BROWSABLE|c:android.intent.category.DEFAULT|d:tether://session",
            ),
            main.filterSignatures(),
        )
        // No other activity in the release manifest.
        assertEquals(1, activities.size)
    }

    @Test
    fun nothingClaimsHttpLinksOrAutoVerifies() {
        for (path in listOf("src/main/AndroidManifest.xml", "src/debug/AndroidManifest.xml")) {
            val root = manifest(path)
            for (filter in root.children("intent-filter")) {
                assertEquals("$path: autoVerify", "", filter.attr("autoVerify"))
            }
            for (data in root.children("data")) {
                val scheme = data.attr("scheme")
                assertFalse("$path claims $scheme", scheme in setOf("http", "https", "intent", "javascript", "content", "file"))
            }
        }
    }

    @Test
    fun theFcmServiceStaysUnexported() {
        val service = manifest("src/main/AndroidManifest.xml").children("service").single { it.attr("name") == ".push.TetherFcmService" }
        assertEquals("false", service.attr("exported"))
    }

    @Test
    fun onlyTetherSessionLinksResolveToTheApp() {
        val pm = context.packageManager
        fun resolvesToUs(uri: String): Boolean =
            pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE), 0)
                .any { it.activityInfo.packageName == context.packageName }
        assertTrue(resolvesToUs("tether://session/s1"))
        assertFalse(resolvesToUs("tether://kill/s1"))
        assertFalse(resolvesToUs("https://tether.example.com/?session=s1"))
        assertFalse(resolvesToUs("http://tether.example.com/?session=s1"))
        val info = pm.getActivityInfo(ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }
}
