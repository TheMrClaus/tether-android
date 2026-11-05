package com.tether.app.ui.chat

import com.tether.app.client.FailedSend
import com.tether.app.client.FailedSendReason
import com.tether.app.client.PendingSendRow
import com.tether.app.client.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** ta-delivery A2: the words of the two upload states a message can be in (spec 5.1 / 5.4). */
class UploadStateLabelsTest {
    private fun row(percent: Int?, status: SendStatus = SendStatus.Sending) =
        PendingSendRow("k", "s1", "send", "look", status, 1, 1, 1500.0, percent)

    private fun failed(reason: FailedSendReason, detail: String?) =
        FailedSend("k", "s1", "send", "look", 1, 1, 1500.0, reason, detail)

    @Test
    fun aMessageWhosePhotosAreUploadingSaysItIsKeptOnTheDeviceWithAPercent() {
        assertEquals("Uploading 42% — kept on this device until it reaches the server", pendingSendLabel(row(42)))
        assertEquals("Uploading 0% — kept on this device until it reaches the server", pendingSendLabel(row(0)))
    }

    @Test
    fun withNoUploadTheLabelsAreUnchanged() {
        assertEquals("Sending — 1 image · 1 KB", pendingSendLabel(row(null)))
    }

    @Test
    fun aFailedUploadSaysTheServersReason() {
        assertEquals(
            "Not sent — the upload failed (An attachment is limited to 9 MiB.)",
            failedSendLabel(failed(FailedSendReason.Upload, "An attachment is limited to 9 MiB.")),
        )
        assertEquals(
            "Not sent — the upload failed (the server did not take the photo)",
            failedSendLabel(failed(FailedSendReason.Upload, null)),
        )
    }
}
