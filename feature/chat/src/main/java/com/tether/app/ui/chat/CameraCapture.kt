package com.tether.app.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID

/*
 * ta-coik.3: the sheet's "Take photo" row. The web reaches the camera through its file input: on
 * Android, Chrome answers `<input type="file">` (chat-view.tsx pickImages/pickFiles, draft-composer.tsx's
 * bare input) with a chooser that offers Camera beside the photo library and the files. The app's
 * image and file rows open the Photo Picker and the document picker, which have no camera, so the
 * camera is its own row: the system camera app (ACTION_IMAGE_CAPTURE through TakePicture) writes the
 * picture into a file this app created, under cache/attachment-capture/, handed over as a
 * [AttachmentCaptureProvider] URI with a one-off write grant. The app declares no CAMERA permission,
 * so the platform asks for nothing (the camera app holds its own).
 *
 * The picture is then staged like any picked one ([AttachmentIntake]: the per-file and total caps,
 * the type from its bytes, the web's image shrink with the EXIF rotation): its bytes came from
 * another app and are untrusted. It is read from the file the app made (never through a URI, so
 * [AttachmentUriPolicy]'s rule that the app never reads its own providers stands), and the file is
 * deleted once read, on a cancelled capture, and by any later capture once it is an hour old.
 */

internal const val ATTACH_ROW_CAMERA = "Take photo"

/** Serves ONLY cache/attachment-capture/ (res/xml/attachment_capture_paths.xml), to the camera app. */
class AttachmentCaptureProvider : FileProvider(com.tether.app.feature.chat.R.xml.attachment_capture_paths)

object CameraCaptures {
    const val DIR = "attachment-capture"

    /** The name a captured picture is staged under (the intake still types it from its bytes). */
    const val PHOTO_NAME = "photo.jpg"

    /** A capture file left behind (the staging never ran) is swept once it is this old. */
    const val STALE_MS: Long = 60L * 60 * 1000

    fun authority(context: Context) = "${context.packageName}.attachmentcapture"

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    /** A fresh, empty, randomly named file for the camera to write to; old leftovers are swept first. */
    fun newTarget(context: Context, now: Long = System.currentTimeMillis()): File {
        val dir = dir(context)
        dir.listFiles()?.forEach { if (now - it.lastModified() >= STALE_MS) it.delete() }
        dir.mkdirs()
        return File(dir, "${UUID.randomUUID()}.jpg").apply { createNewFile() }
    }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /**
     * What a finished capture hands to the stager: the picture, or nothing (cancelled, or the camera
     * wrote nothing; the file is deleted then). [file] must be one [newTarget] made.
     */
    fun result(saved: Boolean, file: File): List<AttachmentSource> =
        if (saved && file.isFile && file.length() > 0) {
            listOf(CapturedPhotoSource(file))
        } else {
            file.delete()
            emptyList()
        }
}

/** A picture the camera app wrote into [file] (an app-made capture file); deleted once its bytes are read. */
class CapturedPhotoSource(private val file: File) : AttachmentSource {
    override val displayName: String get() = CameraCaptures.PHOTO_NAME
    override val reportedSize: Long? get() = file.length().takeIf { it > 0 }
    override val declaredType: String get() = "image/jpeg"

    override fun open(): InputStream? = try {
        object : FileInputStream(file) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.delete()
                }
            }
        }
    } catch (_: java.io.IOException) {
        file.delete()
        null
    }
}

/**
 * The "Take photo" row's action for a composer: opens the camera app on a fresh capture file and
 * hands the picture to [onSources]; [onFlash] gets the words when no camera app can take it. The
 * capture file's path survives the activity being recreated while the camera is open.
 */
@Composable
fun rememberCameraCapture(onSources: (List<AttachmentSource>) -> Unit, onFlash: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latestSources by rememberUpdatedState(onSources)
    val latestFlash by rememberUpdatedState(onFlash)
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val sources = CameraCaptures.result(saved, File(path))
        if (sources.isNotEmpty()) latestSources(sources)
    }
    return remember(context, launcher) {
        {
            val file = try {
                CameraCaptures.newTarget(context)
            } catch (_: java.io.IOException) {
                null
            }
            if (file == null) {
                latestFlash(AttachmentCopy.CAMERA_UNAVAILABLE)
            } else {
                try {
                    pending = file.absolutePath
                    launcher.launch(CameraCaptures.uriFor(context, file))
                } catch (_: ActivityNotFoundException) {
                    pending = null
                    file.delete()
                    latestFlash(AttachmentCopy.CAMERA_UNAVAILABLE)
                } catch (_: IllegalArgumentException) {
                    pending = null
                    file.delete()
                    latestFlash(AttachmentCopy.CAMERA_UNAVAILABLE)
                }
            }
        }
    }
}
