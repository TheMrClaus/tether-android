package com.tether.app.client

import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.Buffer
import okio.source
import java.io.File
import java.io.IOException

/**
 * ta-delivery A2: where an attachment upload may go and how it is signed, decided per call from ONE
 * consistent read of the client's (server, credential) pair (the shape of [FilesAuthority]).
 */
sealed interface UploadAuthority {
    /** The stored sign-in has not been read yet (a worker woke the process before the client loaded it): try again later. */
    data object NotReady : UploadAuthority

    data object SignedOut : UploadAuthority

    data object LocalNetworkBlocked : UploadAuthority

    /**
     * [origin] is the client's own origin string (the one a staged photo and a pending record carry);
     * [base] the server's URL; [sign] RealTetherClient's `authorize` (a paired device's Bearer, or the
     * session cookie with the console Origin), the only place the credential kinds differ.
     */
    class Paired(val origin: String, val base: HttpUrl, val sign: (Request.Builder) -> Request.Builder) : UploadAuthority
}

/** How one `PUT /api/attachments` came out. */
sealed interface UploadOutcome {
    /** 200: stored (a repeat of the same id with the same bytes is a 200 too: success). */
    data class Stored(val id: String, val bytes: Long, val sha256: String) : UploadOutcome

    /** A network error or `400 upload_interrupted`: try again with the SAME id. */
    data class Transient(val reason: String) : UploadOutcome

    /** 404/405 with no server `code`: an old server without the route (R2: only an idle send may then go inline). */
    data object RouteMissing : UploadOutcome

    /** The server said no (or the app refuses before sending). Never retried automatically; [retryable] = a later Send again may work. */
    data class Refused(val status: Int, val code: String?, val reason: String, val retryable: Boolean) : UploadOutcome

    /** Signed out, or signed in to another server than the photo was picked for: the upload is cancelled, nothing is sent. */
    data object WrongServer : UploadOutcome

    /** The sign-in is not loaded or the local network is blocked right now: wait. */
    data object NotReady : UploadOutcome
}

/**
 * The upload of one picked photo to the durable attachment store (tether `lib/server/attachments-http.ts`):
 * `PUT /api/attachments?sessionId&name&mediaType&id` with the raw bytes streamed from the spool [File] (never
 * base64, never held in memory) and a `Content-Length` (the server requires it). [http] MUST NOT follow
 * redirects (RealTetherClient's `authHttp`): OkHttp keeps a hand-set `Cookie` across a cross-host redirect, so a
 * followed 3xx could hand the credential to another origin; a 3xx here is a refusal.
 *
 * Pre-checked here, as the web does for inline attachments: 9 MiB a file ([MAX_FILE_BYTES]). The server may
 * answer an over-long body with a connection reset rather than a 413, so the client never sends one.
 */
class AttachmentUploader(
    private val http: OkHttpClient,
) {
    init {
        require(!http.followRedirects && !http.followSslRedirects) {
            "AttachmentUploader needs a client that never follows redirects (the credential must stay on its origin)"
        }
    }

    /** Blocking (call on an IO dispatcher); cancelling the thread's call is [Call.cancel] via the coroutine. */
    fun upload(
        authority: UploadAuthority,
        expectedOrigin: String,
        sessionId: String,
        uploadId: String,
        name: String,
        mediaType: String,
        file: File,
        onProgress: (sent: Long, total: Long) -> Unit,
        registerCall: (okhttp3.Call) -> Unit = {},
    ): UploadOutcome {
        val paired = when (authority) {
            UploadAuthority.NotReady, UploadAuthority.LocalNetworkBlocked -> return UploadOutcome.NotReady
            UploadAuthority.SignedOut -> return UploadOutcome.WrongServer
            is UploadAuthority.Paired -> authority
        }
        // R4: only for the origin that created it.
        if (paired.origin != expectedOrigin) return UploadOutcome.WrongServer
        val size = file.length()
        if (!file.isFile) return UploadOutcome.Refused(0, "spool_missing", "An attachment is no longer on this device; attach it again and resend.", retryable = false)
        if (size <= 0L) return UploadOutcome.Refused(400, "empty_attachment", "The attachment is empty.", retryable = false)
        if (size > MAX_FILE_BYTES) return UploadOutcome.Refused(413, "attachment_too_large", "An attachment is limited to 9 MiB.", retryable = false)
        val url = paired.base.newBuilder()
            .encodedPath(ROUTE)
            .encodedQuery(
                listOf("sessionId" to sessionId, "name" to name, "mediaType" to mediaType, "id" to uploadId)
                    .joinToString("&") { (k, v) -> "$k=${HttpWorkspaceFiles.encodeUriComponent(v)}" },
            )
            .fragment(null)
            .build()
        val request = paired.sign(Request.Builder().url(url).header("Accept", "application/json").put(FileBody(file, size, mediaType, onProgress))).build()
        // Defence in depth: the credential only ever travels to the origin it belongs to.
        if (request.url.scheme != paired.base.scheme || request.url.host != paired.base.host || request.url.port != paired.base.port) {
            return UploadOutcome.Refused(0, "wrong_origin", "The upload could not be started.", retryable = false)
        }
        val call = http.newCall(request)
        registerCall(call)
        return try {
            call.execute().use { response -> classify(response, uploadId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            UploadOutcome.Transient("The link was down.")
        }
    }

    /** Best-effort `DELETE /api/attachments/<sessionId>/<id>` (a cancelled upload); false = not confirmed. */
    fun delete(authority: UploadAuthority, expectedOrigin: String, sessionId: String, uploadId: String): Boolean {
        val paired = authority as? UploadAuthority.Paired ?: return false
        if (paired.origin != expectedOrigin) return false
        val url = paired.base.newBuilder()
            .encodedPath("$ROUTE/${HttpWorkspaceFiles.encodeUriComponent(sessionId)}/${HttpWorkspaceFiles.encodeUriComponent(uploadId)}")
            .encodedQuery(null)
            .fragment(null)
            .build()
        return try {
            http.newCall(paired.sign(Request.Builder().url(url).delete()).build()).execute().use { it.code == 204 || it.code == 404 }
        } catch (_: IOException) {
            false
        }
    }

    private fun classify(response: okhttp3.Response, uploadId: String): UploadOutcome {
        val status = response.code
        val body = readCapped(response)
        val obj = body?.let(::parseObject)
        val code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull
        val serverReason = (obj?.get("error") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return when {
            status == 200 -> {
                val id = (obj?.get("id") as? JsonPrimitive)?.contentOrNull
                val bytes = (obj?.get("bytes") as? JsonPrimitive)?.longOrNull
                val sha = (obj?.get("sha256") as? JsonPrimitive)?.contentOrNull
                if (id == uploadId && bytes != null && sha != null) UploadOutcome.Stored(id, bytes, sha)
                else UploadOutcome.Refused(status, "bad_reply", "The server's answer to the upload could not be read.", retryable = true)
            }
            // tth-xyp9: a redirect to another host is a sign-in gateway (Cloudflare Access) in front of the server; say so.
            status in 300..399 -> UploadOutcome.Refused(status, null, redirectReason(response), retryable = false)
            status == 400 && code == "upload_interrupted" -> UploadOutcome.Transient(serverReason ?: "The upload was interrupted.")
            (status == 404 || status == 405) && code == null -> UploadOutcome.RouteMissing
            status == 401 || status == 403 -> UploadOutcome.Refused(status, code, serverReason ?: "You are not allowed to upload here.", retryable = false)
            // Server side (tether lib/attachment-store.ts): a same-id PUT already being written answers
            // `409 upload_in_progress` ("retry in a moment") - recoverable, the SAME id is retried with backoff.
            // `409 attachment_id_conflict` (same id, other bytes) stays final.
            status == 409 && code == "upload_in_progress" -> UploadOutcome.Transient(serverReason ?: "That attachment is already being uploaded; retry in a moment.")
            status == 409 -> UploadOutcome.Refused(status, code, serverReason ?: "The server already holds a different attachment for this upload; attach it again.", retryable = false)
            status == 413 -> UploadOutcome.Refused(status, code, serverReason ?: "An attachment is limited to 9 MiB.", retryable = false)
            status == 429 || status >= 500 -> UploadOutcome.Refused(status, code, serverReason ?: "The server could not take the upload right now.", retryable = true)
            else -> UploadOutcome.Refused(status, code, serverReason ?: "The server refused the upload.", retryable = false)
        }
    }

    private fun redirectReason(response: okhttp3.Response): String {
        val location = response.header("Location")?.let { response.request.url.resolve(it) }
        val elsewhere = location != null && location.host != response.request.url.host
        return if (elsewhere) "A sign-in gateway blocked the upload (HTTP ${response.code})." else "The server redirected the upload (HTTP ${response.code})."
    }

    private fun readCapped(response: okhttp3.Response): String? = try {
        val source = response.body.source()
        val buffer = Buffer()
        while (buffer.size <= ERROR_BODY_CAP) {
            if (source.read(buffer, 8192) == -1L) break
        }
        if (buffer.size > ERROR_BODY_CAP) null else buffer.readUtf8()
    } catch (_: IOException) {
        null
    }

    private fun parseObject(text: String): JsonObject? = try {
        if (ServerMessage.nestsDeeperThan(text, ServerMessage.MAX_FRAME_DEPTH)) null
        else TetherJson.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /** The upload body: the spool file streamed once-per-attempt, its length declared, progress reported. */
    private class FileBody(
        private val file: File,
        private val size: Long,
        private val mediaType: String,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
        override fun contentLength(): Long = size
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            file.source().use { input ->
                val buffer = Buffer()
                while (true) {
                    val read = input.read(buffer, CHUNK)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    sent += read
                    onProgress(sent, size)
                }
            }
        }
    }

    companion object {
        const val ROUTE = "/api/attachments"

        /** lib/attachment-store.ts MAX_FILE_BYTES / MAX_MESSAGE_FILES / MAX_MESSAGE_BYTES (inline parity). */
        const val MAX_FILE_BYTES: Long = 9L * 1024 * 1024
        const val MAX_MESSAGE_FILES: Int = 10
        const val MAX_MESSAGE_BYTES: Long = 18L * 1024 * 1024

        private const val CHUNK = 16L * 1024
        private const val ERROR_BODY_CAP = 16L * 1024
    }
}
