package com.tether.app.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** T11.2: what a share intent carries (every extra untrusted), and that the share sheet offers Tether. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentsTest {
    private val image = Uri.parse("content://com.example.photos/media/1")
    private val doc = Uri.parse("content://com.example.docs/doc/2")

    @Test
    fun sendTextIsTheDraftText() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/a")
        assertEquals(SharePayload("https://example.com/a", emptyList(), "text/plain"), ShareIntents.parse(intent))
    }

    @Test
    fun sendStreamIsOneFileWithItsTextIfAny() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, image).putExtra(Intent.EXTRA_TEXT, "caption")
        assertEquals(SharePayload("caption", listOf(image), "image/jpeg"), ShareIntents.parse(intent))
    }

    @Test
    fun sendMultipleIsEveryFileInOrder() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(image, doc))
        assertEquals(SharePayload(null, listOf(image, doc), null), ShareIntents.parse(intent))
    }

    @Test
    fun aStreamOnlyInTheClipIsRead() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf")
        intent.clipData = ClipData.newRawUri("doc", doc)
        assertEquals(listOf(doc), ShareIntents.parse(intent)?.streams)
    }

    @Test
    fun unsupportedOrEmptyIntentsAreIgnored() {
        assertNull(ShareIntents.parse(null))
        assertNull(ShareIntents.parse(Intent(Intent.ACTION_VIEW, image)))
        assertNull(ShareIntents.parse(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertNull(ShareIntents.parse(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "")))
    }

    @Test
    fun wronglyTypedExtrasReadAsAbsent() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, "not a uri").putExtra(Intent.EXTRA_TEXT, 42)
        assertNull(ShareIntents.parse(intent))
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, image).putExtra(Intent.EXTRA_TEXT, "t")
        assertEquals(SharePayload("t", emptyList(), null), ShareIntents.parse(multiple))
    }

    @Test
    fun theShareSheetOffersTetherForTextImagesAndFiles() {
        val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
        fun resolves(intent: Intent) = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo.name == ShareActivity::class.java.name }
        assertTrue(resolves(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertTrue(resolves(Intent(Intent.ACTION_SEND).setType("image/jpeg")))
        assertTrue(resolves(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")))
    }
}
