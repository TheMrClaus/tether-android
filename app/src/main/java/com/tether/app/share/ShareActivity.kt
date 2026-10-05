package com.tether.app.share

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.lifecycleScope
import com.tether.app.MainActivity
import com.tether.app.ui.chat.AttachmentUriPolicy
import com.tether.app.ui.chat.ContentUriSource
import com.tether.app.ui.chat.SharedAttachments
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T11.2: the system share sheet's target (ACTION_SEND / ACTION_SEND_MULTIPLE). It draws nothing. The
 * sender's grant on its `content://` URIs lasts only while this activity does, so it reads every
 * shared file at once, under the attachment intake's rules ([SharedAttachments]), into the app's
 * cache; then it puts the share in the [ShareInbox], brings Tether's own task forward (as a
 * notification tap does) and finishes. The shell then asks where the share goes. Leaving this
 * activity before the read is done drops the share.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = ShareIntents.parse(intent)
        if (payload == null) {
            finish()
            return
        }
        val id = ShareInbox.nextId()
        val dir = File(shareRoot(this), id.toString())
        val policy = AttachmentUriPolicy.of(this)
        val resolver = contentResolver
        lifecycleScope.launch {
            val files = if (payload.streams.isEmpty()) {
                emptyList()
            } else {
                val sources = payload.streams.map { ContentUriSource(resolver, it, policy, clipType = payload.type) }
                val job = currentCoroutineContext()
                withContext(Dispatchers.IO) { SharedAttachments.buffer(sources, dir, active = { job.isActive }) }
            }
            if (files == null) {
                dir.deleteRecursively()
                finish()
                return@launch
            }
            ShareInbox.offer(PendingShare(id, payload.text, files, dir.takeIf { files.isNotEmpty() }))
            startActivity(mainIntent(this@ShareActivity))
            finish()
        }
    }

    companion object {
        /** MainActivity's action for "a share is waiting" (it reads nothing from the intent). */
        const val ACTION_SHARED = "com.tether.app.action.SHARED"

        fun shareRoot(context: Context): File = File(context.cacheDir, "shared")

        @VisibleForTesting
        internal fun mainIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_SHARED).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
    }
}
