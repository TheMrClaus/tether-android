package com.tether.app.client

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** ta-delivery A2: the seam between the upload ledger and the platform's background work (a fake in the tests). */
interface UploadScheduler {
    /** Run [AttachmentUploads.run] for [id] when the network is there; survives backgrounding and process death. */
    fun enqueue(id: String)

    fun cancel(id: String)

    fun cancelAll()
}

/**
 * WorkManager, expedited (minSdk 34: no foreground service and no foreground-service permission is involved;
 * when the expedited quota is spent the work runs as ordinary work instead). One unique work per upload id, so a
 * second enqueue of the same id is a no-op and a resumed upload is never a second one.
 */
class WorkManagerUploadScheduler(private val context: Context) : UploadScheduler {
    private val work: WorkManager get() = WorkManager.getInstance(context)

    override fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(id: String) {
        work.cancelUniqueWork(workName(id))
    }

    override fun cancelAll() {
        work.cancelAllWorkByTag(TAG)
    }

    companion object {
        const val TAG = "attachment-upload"
        fun workName(id: String) = "attachment-upload-$id"
    }
}

/** The process-wide handle a worker reaches the ledger through (a worker is built by WorkManager, not by us). */
object UploadRuntime {
    /** Set by the application: how a worker obtains the ledger (creating the client, which loads the sign-in, if need be). */
    @Volatile
    var provider: ((Context) -> AttachmentUploads?)? = null
}

/** One upload per run; WorkManager retries with backoff only while the ledger says [UploadRunResult.Retry]. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val uploads = UploadRuntime.provider?.invoke(applicationContext) ?: return Result.failure()
        return when (uploads.run(id)) {
            UploadRunResult.Done, UploadRunResult.Gone -> Result.success()
            UploadRunResult.Retry -> Result.retry()
        }
    }

    companion object {
        const val KEY_ID = "uploadId"
    }
}
