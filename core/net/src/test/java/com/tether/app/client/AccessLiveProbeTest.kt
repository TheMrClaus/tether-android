package com.tether.app.client

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assume.assumeTrue
import org.junit.Test

/** tth-xyp9: env-gated (XYP9_LIVE=1) credential-free probe: the real AttachmentUploader over the real transport to the public host with a bogus Bearer. */
class AccessLiveProbeTest {
    @Test
    fun liveUploaderAgainstPublicHostWithBogusBearer() {
        assumeTrue(System.getenv("XYP9_LIVE") == "1" && !System.getenv("XYP9_LIVE_URL").isNullOrBlank())
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val base = (System.getenv("XYP9_LIVE_URL") ?: error("XYP9_LIVE_URL unset")).toHttpUrl()
        val file = File.createTempFile("xyp9", ".bin").apply { writeBytes(ByteArray(4_000_000) { (it % 251).toByte() }); deleteOnExit() }
        val auth = UploadAuthority.Paired(base.toString().trimEnd('/'), base) { it.header("Authorization", "Bearer invalid-diagnostic-token") }
        val out = AttachmentUploader(http).upload(auth, base.toString().trimEnd('/'), "s1", "00000000-0000-4000-8000-000000000000", "p.jpg", "image/jpeg", file, { _, _ -> })
        println("XYP9 live outcome=$out")
    }
}
