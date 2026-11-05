package com.tether.app.client

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ta-delivery A2 on `androidx.work:work-testing`: the worker runs the ledger's upload; the scheduler enqueues unique, networked, expedited work. */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val dirs = ArrayList<File>()
    private val bytes = ByteArray(900) { it.toByte() }
    private val origin = "http://worker.test"

    @After
    fun tearDown() {
        UploadRuntime.provider = null
        server.shutdown()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun hub(scheduler: UploadScheduler = FakeUploadScheduler()): AttachmentUploads {
        server.start()
        val root = File(java.nio.file.Files.createTempDirectory("a2-worker").toFile().also { dirs += it }, "u")
        return AttachmentUploads(root, scheduler).also {
            it.bind(
                AttachmentUploader(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()),
                { UploadAuthority.Paired(origin, server.url("/")) { r -> r.header("Authorization", "Bearer tok") } },
            ) {}
        }
    }

    private fun worker(id: String) = TestListenableWorkerBuilder<UploadWorker>(context)
        .setInputData(workDataOf(UploadWorker.KEY_ID to id))
        .build()

    @Test
    fun theWorkerRunsTheLedgersUploadAndSucceeds() {
        val hub = hub()
        val id = hub.begin(origin, "s1", "p.png", "image/png", bytes)!!
        hub.bind(listOf(id), "rec", PendingInput.KIND_SEND, origin, "s1")
        UploadRuntime.provider = { hub }
        server.enqueue(MockResponse().setBody("""{"id":"$id","name":"p.png","mediaType":"image/png","bytes":${bytes.size},"sha256":"x"}"""))
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(id).doWork() })
        assertEquals(UploadPhase.Stored, hub.state(id)!!.phase)
    }

    @Test
    fun aWorkerThatCannotReachTheServerAsksForARetryWithTheSameId() {
        val hub = hub()
        val id = hub.begin(origin, "s1", "p.png", "image/png", bytes)!!
        hub.bind(listOf(id), "rec", PendingInput.KIND_SEND, origin, "s1")
        UploadRuntime.provider = { hub }
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker(id).doWork() })
        assertEquals(UploadPhase.Pending, hub.state(id)!!.phase)
    }

    @Test
    fun aWorkerForAnUnknownOrCancelledUploadSucceedsDoingNothing() {
        val hub = hub()
        UploadRuntime.provider = { hub }
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker("00000000-0000-4000-8000-000000000000").doWork() })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theSchedulerEnqueuesOneUniqueNetworkedExpeditedWorkPerUploadAndCancelsIt() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val wm = WorkManager.getInstance(context)
        val scheduler = WorkManagerUploadScheduler(context)
        val id = "11111111-1111-4111-8111-111111111111"
        scheduler.enqueue(id)
        scheduler.enqueue(id) // KEEP: a resumed upload is never a second one
        val infos = wm.getWorkInfosForUniqueWork(WorkManagerUploadScheduler.workName(id)).get()
        assertEquals(1, infos.size)
        assertTrue(infos.single().tags.contains(WorkManagerUploadScheduler.TAG))
        scheduler.cancel(id)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(WorkManagerUploadScheduler.workName(id)).get().single().state)
    }
}
