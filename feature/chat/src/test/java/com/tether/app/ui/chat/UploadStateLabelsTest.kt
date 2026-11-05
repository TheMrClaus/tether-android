package com.tether.app.ui.chat

import com.tether.app.client.FailedSend
import com.tether.app.client.FailedSendReason
import com.tether.app.client.PendingSendRow
import com.tether.app.client.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** ta-delivery A2: the words of the two upload states a message can be in (spec 5.1 / 5.4). */
class UploadStateLabelsTest {
    private fun row(percent: Int?, status: SendStatus = SendStatus.Sending, kept: Boolean = true, count: Int = 1) =
        PendingSendRow("k", "s1", "send", "look", status, count, count, 1500.0, percent, kept)

    private fun failed(reason: FailedSendReason, detail: String?) =
        FailedSend("k", "s1", "send", "look", 1, 1, 1500.0, reason, detail)

    @Test
    fun aMessageWhosePhotosAreUploadingSaysV1SummaryFirstWithNoPercentInTheWords() {
        val tail = "Uploading — kept on this device until it reaches the server"
        assertEquals("1 image · 1 KB · $tail", pendingSendLabel(row(42)))
        assertEquals("1 image · 1 KB · $tail", pendingSendLabel(row(0)))
        assertEquals("1 image · 1 KB · $tail", pendingSendLabel(row(99)))
        assertEquals("1 image · 1 KB · $tail", uploadingLabel(row(42)))
        // No attachment summary: just the words.
        assertEquals(tail, pendingSendLabel(row(42, count = 0)))
    }

    @Test
    fun aRecordHeldOnlyInMemoryDoesNotClaimTheDeviceKeepsIt() {
        val tail = "Uploading — may be lost if the app closes before it reaches the server"
        assertEquals("1 image · 1 KB · $tail", pendingSendLabel(row(42, kept = false)))
        assertEquals(tail, uploadingLabel(row(42, kept = false, count = 0)))
    }

    @Test
    fun theLinkStateWinsOverAnUploadPercent() {
        assertEquals(
            "Waiting for link — reconnecting, your message will be sent automatically",
            pendingSendLabel(row(0, SendStatus.Waiting)),
        )
    }

    @Test
    fun withNoUploadTheLabelsAreUnchanged() {
        assertEquals("Sending — 1 image · 1 KB", pendingSendLabel(row(null)))
    }

    @Test
    fun aFailedUploadSaysTheServersReason() {
        // spec 4.1b B.1: sentence, then the reason, then " · {summary}"; an empty reason is no sentence.
        assertEquals(
            "Not sent — the upload failed. An attachment is limited to 9 MiB. · 1 image · 1 KB",
            failedSendLabel(failed(FailedSendReason.Upload, "An attachment is limited to 9 MiB.")),
        )
        assertEquals(
            "Not sent — the upload failed. · 1 image · 1 KB",
            failedSendLabel(failed(FailedSendReason.Upload, null)),
        )
        assertEquals(
            "Not sent — the upload failed. The link was down. · 1 image · 1 KB",
            failedSendLabel(failed(FailedSendReason.Upload, "The link was down.")),
        )
    }

    @Test
    fun aWaitingRecordTheDeviceDoesNotKeepSaysSoInTheStripAndTheBubble() {
        val kept = row(null, SendStatus.Waiting)
        val lost = row(null, SendStatus.Waiting, kept = false)
        assertEquals("Waiting for link — kept on this device, sends when the connection returns", sendRowLabel(listOf(kept)))
        assertEquals("Waiting for link — sends when the connection returns; may be lost if the app closes first", sendRowLabel(listOf(lost)))
        // ANY waiting record not kept turns the whole line.
        assertEquals(
            "Waiting for link — sends when the connection returns; may be lost if the app closes first",
            sendRowLabel(listOf(kept, lost)),
        )
        assertEquals("Waiting for link — reconnecting, your message will be sent automatically", pendingSendLabel(kept))
        assertEquals("Waiting for link — reconnecting; may be lost if the app closes first", pendingSendLabel(lost))
    }
}
