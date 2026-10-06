package com.tether.app.ui.files

import android.net.Uri
import android.view.Surface
import android.widget.MediaController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.FileHead
import com.tether.app.client.FilesResult
import com.tether.app.client.RangeRead
import com.tether.app.client.UploadSource
import com.tether.app.client.WorkspaceBreadcrumb
import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.client.WorkspaceFileListing
import com.tether.app.client.WorkspaceFiles
import com.tether.app.client.WorkspaceMutation
import java.io.OutputStream
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The web's seeded scenario (parity-seed.mjs seedProject): `<ws>/parity-app`, all at FIXED_EPOCH. */
object FilesFixtures {
    const val ROOT = "/tmp/tether-parity-s04/main/ws/parity-app"
    const val SESSION = "Summarize the README"

    /** 2026-01-01T00:00:00Z, the seeder's FIXED_EPOCH_S. */
    const val EPOCH_MS = 1_767_225_600_000.0
    val env = FileFormatEnv(Locale.US, ZoneOffset.UTC)

    fun crumbs(path: String): List<WorkspaceBreadcrumb> {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        val out = mutableListOf(WorkspaceBreadcrumb("/", "/"))
        var cursor = ""
        for (part in parts) {
            cursor += "/$part"
            out += WorkspaceBreadcrumb(part, cursor)
        }
        return out
    }

    fun dir(name: String, parent: String = ROOT) = WorkspaceFileEntry(name, "$parent/$name", 4096, EPOCH_MS, true)
    fun file(name: String, size: Long, parent: String = ROOT) = WorkspaceFileEntry(name, "$parent/$name", size, EPOCH_MS, false)

    val docs = dir("docs")
    val src = dir("src")
    val packageJson = file("package.json", 95)
    val readme = file("README.md", 65)
    const val README_TEXT = "# parity-app\n\nA tiny fixture project for the parity screenshots.\n"

    fun listing(path: String = ROOT, entries: List<WorkspaceFileEntry> = listOf(docs, src, packageJson, readme)) =
        WorkspaceFileListing(path, path.substringBeforeLast('/', "").ifEmpty { "/" }.takeIf { path != "/" }, crumbs(path), entries)
}

/**
 * A recording [WorkspaceFiles]: every call lands in [calls] as "op arg arg"; answers come from
 * the maps (default: success). A [gate] makes the next call of that op wait until completed.
 */
class FakeFiles : WorkspaceFiles {
    val calls = mutableListOf<String>()
    val listings = mutableMapOf<String, FilesResult<WorkspaceFileListing>>()
    val texts = mutableMapOf<String, FilesResult<String>>()
    val failures = mutableMapOf<String, FilesResult.Failed>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val uploaded = mutableListOf<Pair<String, ByteArray>>()

    /** Gated calls finish even when cancelled (an HTTP response that lands as the call is cancelled). */
    var gatesIgnoreCancellation = false

    private suspend fun enter(op: String, vararg args: String) {
        calls += (listOf(op) + args).joinToString(" ")
        val gate = gates.remove(op) ?: return
        if (gatesIgnoreCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
    }

    private fun mutation(op: String, parent: String): FilesResult<WorkspaceMutation> = failures[op] ?: FilesResult.Ok(WorkspaceMutation(parent))

    override suspend fun list(path: String): FilesResult<WorkspaceFileListing> {
        enter("list", path)
        return listings[path] ?: FilesResult.Failed("That folder is not available.", 404)
    }

    override suspend fun mkdir(parent: String, name: String) = enter("mkdir", parent, name).let { mutation("mkdir", parent) }
    override suspend fun touch(parent: String, name: String) = enter("touch", parent, name).let { mutation("touch", parent) }
    override suspend fun rename(path: String, name: String) = enter("rename", path, name).let { mutation("rename", path) }
    override suspend fun move(path: String, destination: String) = enter("move", path, destination).let { mutation("move", destination) }
    override suspend fun copy(path: String, destination: String) = enter("copy", path, destination).let { mutation("copy", destination) }
    override suspend fun delete(path: String) = enter("delete", path).let { mutation("delete", path) }

    override suspend fun upload(parent: String, name: String, source: UploadSource, overwrite: Boolean): FilesResult<WorkspaceMutation> {
        enter("upload", parent, name)
        uploadThrows?.let { throw it }
        uploaded += name to source.open().use { it.readBytes() }
        return failures["upload:$name"] ?: failures["upload"] ?: FilesResult.Ok(WorkspaceMutation(parent))
    }

    override suspend fun head(path: String): FilesResult<FileHead> = enter("head", path).let { FilesResult.Ok(FileHead(0, null)) }

    override suspend fun readText(path: String, listedSize: Long): FilesResult<String> {
        enter("readText", path, listedSize.toString())
        return texts[path] ?: FilesResult.Failed("This text file could not be opened.", 404)
    }

    /** Bodies served by [download]; a path without one fails like a 404. */
    val downloads = mutableMapOf<String, ByteArray>()
    var uploadThrows: RuntimeException? = null

    /** Files [readRange] serves: path -> size. Their bytes are `position % 251`, so no body is ever held. */
    val virtualFiles = mutableMapOf<String, Long>()

    /** The origin [readRange] answers from; a pinned read for another one is refused, as the real client does. */
    var rangeOrigin = "https://server-a:443"

    override suspend fun readRange(path: String, offset: Long, length: Int, pinnedOrigin: String?): FilesResult<RangeRead> {
        enter("readRange", path, offset.toString(), length.toString())
        if (pinnedOrigin != null && pinnedOrigin != rangeOrigin) return FilesResult.Failed("This file could not be opened.")
        failures["readRange"]?.let { return it }
        val size = virtualFiles[path] ?: return FilesResult.Failed("This file could not be opened.", 404)
        if (offset >= size) return FilesResult.Ok(RangeRead(ByteArray(0), size, rangeOrigin))
        val count = minOf(length.toLong(), size - offset).toInt()
        return FilesResult.Ok(RangeRead(ByteArray(count) { ((offset + it) % 251).toByte() }, size, rangeOrigin))
    }

    /** The byte cap each [download] was asked for, in order. */
    val downloadCaps = mutableListOf<Long>()

    override suspend fun download(path: String, maxBytes: Long, sink: OutputStream): FilesResult<Long> {
        enter("download", path)
        downloadCaps += maxBytes
        val body = downloads[path] ?: return FilesResult.Failed("This file could not be opened.", 404)
        sink.write(body)
        return FilesResult.Ok(body.size.toLong())
    }
}

/** A [VideoPlayer] that only records: its phase is whatever a test sets, and it counts releases. */
class FakeVideoPlayer(val entry: WorkspaceFileEntry, private val onFailed: () -> Unit) : VideoPlayer {
    override var phase: VideoPhase by mutableStateOf(VideoPhase.Opening)
    override var playing: Boolean by mutableStateOf(false)
    var releases = 0
    var pauses = 0
    var attached: Surface? = null
    val released: Boolean get() = releases > 0

    override val control = object : MediaController.MediaPlayerControl {
        override fun start() { playing = true }
        override fun pause() { playing = false }
        override fun getDuration() = 60_000
        override fun getCurrentPosition() = 0
        override fun seekTo(pos: Int) = Unit
        override fun isPlaying() = playing
        override fun getBufferPercentage() = 0
        override fun canPause() = true
        override fun canSeekBackward() = true
        override fun canSeekForward() = true
        override fun getAudioSessionId() = 0
    }

    override fun attachSurface(surface: Surface) { attached = surface }
    override fun detachSurface(surface: Surface) { if (attached === surface) attached = null }
    override fun pause() { pauses++; playing = false }
    override fun release() { releases++ }

    /** The player gives up, as the real one does: phase Failed, then the state's callback. */
    fun fail() {
        phase = VideoPhase.Failed
        onFailed()
    }
}

class FakePlatform : BrowserPlatform {
    val calls = mutableListOf<String>()
    var image: ImageLoad = ImageLoad.Failed("no image")
    var imageGate: CompletableDeferred<Unit>? = null
    var svg: SvgLoad = SvgLoad.Failed("no svg")
    var svgGate: CompletableDeferred<Unit>? = null
    val players = mutableListOf<FakeVideoPlayer>()

    override suspend fun loadSvg(files: WorkspaceFiles, entry: WorkspaceFileEntry): SvgLoad {
        calls += "loadSvg ${entry.path}"
        svgGate?.await()
        return svg
    }

    override fun openVideo(files: WorkspaceFiles, entry: WorkspaceFileEntry, onFailed: () -> Unit): VideoPlayer {
        calls += "openVideo ${entry.path}"
        return FakeVideoPlayer(entry, onFailed).also { players += it }
    }

    override suspend fun loadImage(files: WorkspaceFiles, entry: WorkspaceFileEntry): ImageLoad {
        calls += "loadImage ${entry.path}"
        imageGate?.await()
        return image
    }

    override suspend fun saveTo(files: WorkspaceFiles, entry: WorkspaceFileEntry, target: Uri): FilesResult<Long> {
        calls += "saveTo ${entry.path}"
        saveFailure?.let { throw it }
        return FilesResult.Ok(entry.size)
    }

    var share: FilesResult<ShareReady> = FilesResult.Failed("The file could not be prepared for sharing.")
    var shareGate: CompletableDeferred<Unit>? = null
    var saveFailure: Throwable? = null

    override suspend fun shareCopy(files: WorkspaceFiles, entry: WorkspaceFileEntry): FilesResult<ShareReady> {
        calls += "shareCopy ${entry.path}"
        shareGate?.await()
        return share
    }

    override fun claimShare(share: ShareReady) {
        calls += "claim ${share.id}"
    }

    override fun discardShare(share: ShareReady) {
        calls += "discard ${share.id}"
    }

    override fun discardUnclaimedShares() {
        calls += "discardUnclaimed"
    }

    override fun sweep(mode: SweepMode) {
        calls += "sweep $mode"
    }
}

fun bytesSource(bytes: ByteArray, length: Long? = bytes.size.toLong()) = object : UploadSource {
    override val length: Long? = length
    override fun open() = bytes.inputStream()
}
