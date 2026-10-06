package com.tether.app.ui.files

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
import com.tether.app.client.UploadSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/*
 * ta-coik.67: the workspace Upload key's "Take photo". The web's Upload is a bare
 * `<input type="file" multiple>` (workspace-file-browser.tsx:480-486 at 29537e0), and Android
 * Chrome answers that input with a chooser that offers the Camera beside the files. The app's
 * Upload key therefore opens a two-row chooser ([UploadChooserContent]): "Choose files" (the
 * system document picker, as before) and "Take photo" (ACTION_IMAGE_CAPTURE through TakePicture).
 *
 * The camera app writes the picture into a file this module made under cache/workspace-capture/,
 * handed over as a [WorkspaceCaptureProvider] URI with a one-off write grant: its OWN provider,
 * serving only that folder (res/xml/workspace_capture_paths.xml), apart from the read-only
 * [WorkspaceFileProvider] that serves shared copies. Not exported; no CAMERA permission is
 * declared (the camera app holds its own). The picture is uploaded like a picked file, read from
 * the app-made file (never through a URI), and the file is deleted when the upload ends for any
 * reason, on a cancelled capture, and (stale ones) at the next capture and at process start.
 */

/** Serves ONLY cache/workspace-capture/ (res/xml/workspace_capture_paths.xml), to the camera app. */
class WorkspaceCaptureProvider : FileProvider(com.tether.app.feature.files.R.xml.workspace_capture_paths) {
    override fun onCreate(): Boolean {
        val created = super.onCreate()
        context?.let { context -> FileCache.sweepInBackground { UploadCaptures.sweepStale(context) } }
        return created
    }
}

object UploadCaptures {
    const val DIR = "workspace-capture"

    /**
     * The name a captured picture is uploaded as: `IMG_yyyyMMdd_HHmmss.jpg`, as camera pictures are
     * named, so a second photo in the same folder is a new file and never replaces the first.
     */
    fun photoName(now: Long = System.currentTimeMillis()): String =
        "IMG_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(java.util.Date(now)) + ".jpg"

    /** A capture file left behind (the upload never ran) is swept once it is this old. */
    const val STALE_MS: Long = 60L * 60 * 1000

    const val CAMERA_UNAVAILABLE = "No camera app is available to take a photo."

    fun authority(context: Context) = "${context.packageName}.workspacecapture"

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    fun sweepStale(context: Context, now: Long = System.currentTimeMillis()) {
        dir(context).listFiles()?.forEach { if (now - it.lastModified() >= STALE_MS) it.deleteRecursively() }
    }

    /** A fresh, empty, randomly named file for the camera to write to; old leftovers are swept first. */
    fun newTarget(context: Context, now: Long = System.currentTimeMillis()): File {
        sweepStale(context, now)
        val dir = dir(context)
        dir.mkdirs()
        return File(dir, "${UUID.randomUUID()}.jpg").apply { if (!createNewFile()) throw IOException("could not create a capture file") }
    }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /**
     * What a finished capture hands to the upload: the picture, or null (cancelled, or the camera
     * wrote nothing; the file is deleted then). [file] must be one [newTarget] made.
     */
    fun result(saved: Boolean, file: File): PickedUpload? =
        if (saved && file.isFile && file.length() > 0) {
            PickedUpload(photoName(), CapturedPhotoSource(file), onFinished = { file.delete() })
        } else {
            file.delete()
            null
        }
}

/** A picture the camera app wrote into [file] (an app-made capture file), streamed from it. */
class CapturedPhotoSource(private val file: File) : UploadSource {
    override val length: Long? get() = file.length()

    override fun open(): InputStream = FileInputStream(file)
}

/**
 * The "Take photo" action: opens the camera app on a fresh capture file and hands the picture to
 * [onPhoto]; [onUnavailable] gets the words when no camera app can take it. The capture file's
 * path survives the activity being recreated while the camera is open.
 */
@Composable
fun rememberUploadCapture(onPhoto: (PickedUpload) -> Unit, onUnavailable: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latestPhoto by rememberUpdatedState(onPhoto)
    val latestUnavailable by rememberUpdatedState(onUnavailable)
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = pending ?: return@rememberLauncherForActivityResult
        pending = null
        UploadCaptures.result(saved, File(path))?.let { latestPhoto(it) }
    }
    return remember(context, launcher) {
        {
            val file = try {
                UploadCaptures.newTarget(context)
            } catch (_: IOException) {
                null
            }
            if (file == null) {
                latestUnavailable(UploadCaptures.CAMERA_UNAVAILABLE)
            } else {
                try {
                    pending = file.absolutePath
                    launcher.launch(UploadCaptures.uriFor(context, file))
                } catch (_: ActivityNotFoundException) {
                    pending = null
                    file.delete()
                    latestUnavailable(UploadCaptures.CAMERA_UNAVAILABLE)
                } catch (_: IllegalArgumentException) {
                    pending = null
                    file.delete()
                    latestUnavailable(UploadCaptures.CAMERA_UNAVAILABLE)
                }
            }
        }
    }
}
