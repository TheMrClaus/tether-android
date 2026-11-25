package com.tether.app.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.BrowserProfilesReply
import com.tether.app.client.BrowserProfilesRequest
import com.tether.app.protocol.BrowserProfileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the block needs to ask the server: one request, one reply (the client's `browserProfiles`). */
fun interface BrowserProfilesSource {
    suspend fun request(request: BrowserProfilesRequest): BrowserProfilesReply
}

/** A row's own refusal line (the web's `rowError`). */
data class BrowserProfileRowError(val id: String, val text: String)

/** The row being renamed (the web's `editing`). */
data class BrowserProfileEdit(val id: String, val name: String)

/**
 * components/browser-profiles-settings.tsx as state: the list (null until the first answer), the load error with Try again,
 * the create form, one rename, one delete confirm, one row error, and `busy` while this list's own create / rename / delete is in
 * flight. A refused action shows the reply's own sentence; the reply's list replaces the shown one when it has rows or succeeded.
 */
@Stable
class BrowserProfilesController(
    private val source: BrowserProfilesSource,
    private val scope: CoroutineScope,
    /** A state made by the caller (a picture's): shown as built, nothing is read. */
    private val seed: List<BrowserProfileInfo>? = null,
) {
    var profiles: List<BrowserProfileInfo>? by mutableStateOf(seed)
        private set
    var loadError by mutableStateOf("")
        private set
    var createName by mutableStateOf("")
        private set
    var createError by mutableStateOf("")
        private set
    var editing: BrowserProfileEdit? by mutableStateOf(null)
        private set
    var rowError: BrowserProfileRowError? by mutableStateOf(null)
        private set
    var confirmingId: String? by mutableStateOf(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** The first read, on show. */
    fun shown() {
        if (seed != null) return
        scope.launch {
            val reply = source.request(BrowserProfilesRequest.List)
            if (reply.ok) profiles = reply.profiles else loadError = BrowserProfilesCopy.errorText(reply)
        }
    }

    /** Try again. */
    fun refresh() {
        scope.launch {
            val reply = source.request(BrowserProfilesRequest.List)
            if (reply.ok) {
                profiles = reply.profiles
                loadError = ""
            } else {
                loadError = BrowserProfilesCopy.errorText(reply)
            }
        }
    }

    /** The list follows the world: a quiet re-read when the screen comes back; none while this list's own action is in flight. */
    fun refreshQuietly() {
        if (busy || seed != null) return
        scope.launch {
            val reply = source.request(BrowserProfilesRequest.List)
            if (!busy && reply.ok) {
                profiles = reply.profiles
                loadError = ""
            }
        }
    }

    fun editCreateName(value: String) {
        createName = value
        createError = ""
    }

    fun editRenameName(value: String) {
        editing = editing?.copy(name = value)
    }

    fun startRename(profile: BrowserProfileInfo) {
        editing = BrowserProfileEdit(profile.id, profile.name)
        confirmingId = null
        rowError = null
    }

    fun cancelRename() {
        editing = null
        rowError = null
    }

    fun askDelete(id: String) {
        confirmingId = id
        editing = null
        rowError = null
    }

    fun keepIt() {
        confirmingId = null
        rowError = null
    }

    fun create() {
        val problem = BrowserProfilesCopy.nameProblem(createName)
        if (problem != null) {
            createError = problem
            return
        }
        createError = ""
        run(BrowserProfilesRequest.Create(createName.trim()), { createError = it }) { createName = "" }
    }

    fun rename() {
        val edit = editing ?: return
        val problem = BrowserProfilesCopy.nameProblem(edit.name)
        if (problem != null) {
            rowError = BrowserProfileRowError(edit.id, problem)
            return
        }
        rowError = null
        run(BrowserProfilesRequest.Rename(edit.id, edit.name.trim()), { rowError = BrowserProfileRowError(edit.id, it) }) { editing = null }
    }

    fun delete(id: String) {
        rowError = null
        run(BrowserProfilesRequest.Delete(id), { rowError = BrowserProfileRowError(id, it) }) { confirmingId = null }
    }

    private fun run(request: BrowserProfilesRequest, onRefused: (String) -> Unit, onDone: () -> Unit) {
        busy = true
        scope.launch {
            try {
                val reply = source.request(request)
                if (reply.profiles.isNotEmpty() || reply.ok) profiles = reply.profiles
                if (reply.ok) onDone() else onRefused(BrowserProfilesCopy.errorText(reply))
            } finally {
                busy = false
            }
        }
    }
}
