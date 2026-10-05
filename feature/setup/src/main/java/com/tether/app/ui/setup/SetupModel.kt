package com.tether.app.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.BinaryCheck
import com.tether.app.client.EngineDetection
import com.tether.app.client.SetupApi
import com.tether.app.client.SetupCall
import com.tether.app.client.SetupCopy
import com.tether.app.client.SetupFinish
import com.tether.app.client.SetupState
import com.tether.app.protocol.model.DirectoryListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * T10.6 (ta-jwbs): the wizard's state, ported from tether 90fbb9f app/setup/page.tsx SetupPage (:84-290).
 * Pure of Compose UI (snapshot state only), so every rule below is tested without a screen.
 */

/** page.tsx :71 `STEP_TITLES`: the seven stations. GitHub and Claude accounts are skippable (:260-262). */
enum class SetupStep(val title: String) {
    Welcome("Welcome"),
    Operator("Operator"),
    Harnesses("Harnesses"),
    Workspace("Workspace"),
    GitHub("GitHub"),
    ClaudeAccounts("Claude accounts"),
    Review("Review"),
}

/** page.tsx :55-61 `ENGINE_META`; [overrideKey] is the server setting that carries a located install. */
class EngineMeta(val label: String, val bin: String, val overrideKey: String, val blurb: String)

object SetupEngines {
    /** In the page's order (the Welcome inventory lists them as they are here). */
    val meta: Map<String, EngineMeta> = linkedMapOf(
        "claude" to EngineMeta("Claude Code", "claude", "claudeCliPath", "Anthropic"),
        "codex" to EngineMeta("Codex", "codex", "codexCommand", "OpenAI"),
        "opencode" to EngineMeta("OpenCode", "opencode", "opencodeCommand", "SST"),
        "reasonix" to EngineMeta("Reasonix", "reasonix", "reasonixCommand", "Reasonix"),
        "pi" to EngineMeta("Pi", "pi", "piCommand", "pi.dev"),
    )

    /** page.tsx :63 `ENGINE_GLYPH`. */
    val glyph: Map<String, String> = mapOf("claude" to "C", "codex" to "X", "opencode" to "O", "reasonix" to "R", "pi" to "P", "fake" to "F")
}

enum class Isolation { Open, Guided }

/** What a folder browse is for: the workspace root, or the folder holding one engine's executable. */
sealed interface PickerTarget {
    data object Workspace : PickerTarget
    data class Locate(val engine: String) : PickerTarget
}

class SetupWizardModel(
    private val api: SetupApi,
    private val scope: CoroutineScope,
    /** page.tsx :222-226: the restart poll's interval. */
    private val restartPollMs: Long = 1_500,
) {
    /** Called when the operator goes on to sign in: "Go to sign-in", the restart poll's end, or a 401 (setup is done). */
    var onSignIn: () -> Unit = {}

    var step by mutableIntStateOf(0)
        private set
    var state by mutableStateOf<SetupState?>(null)
        private set
    var stateError by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    // Operator.
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var confirmPassword by mutableStateOf("")
    var showPassword by mutableStateOf(false)

    // Harnesses.
    var engines by mutableStateOf<List<String>>(emptyList())
        private set
    private var enginesTouched = false
    var detected by mutableStateOf<Map<String, EngineDetection>>(emptyMap())
        private set
    var detecting by mutableStateOf(false)
        private set
    var locators by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var locatorChecks by mutableStateOf<Map<String, BinaryCheck?>>(emptyMap())
        private set
    var useBundled by mutableStateOf(false)
    var isolation by mutableStateOf(Isolation.Open)

    // Workspace.
    var workspaceRoot by mutableStateOf("")
        private set

    // The folder picker (the shared FolderPickerDialog over /api/setup/browse).
    var picker by mutableStateOf<PickerTarget?>(null)
        private set
    var pickerListing by mutableStateOf<DirectoryListing?>(null)
        private set
    var pickerError by mutableStateOf<String?>(null)
        private set
    private var browseGeneration = 0

    // Finish.
    var finish by mutableStateOf<SetupFinish?>(null)
        private set

    fun forced(key: String): Boolean = state?.forced(key) == true

    // ---- loading ----------------------------------------------------------------------------

    /** page.tsx :135-165 loadState. A 401 is "setup is done, go sign in" (issue #27), not a retryable error. */
    fun load() {
        if (loading) return
        stateError = ""
        loading = true
        scope.launch {
            try {
                loadState()
            } finally {
                loading = false
            }
        }
    }

    private var loading = false

    internal suspend fun loadState() {
        when (val call = api.state()) {
            is SetupCall.Ok -> {
                val s = call.value
                state = s
                s.settings["headlessModes"]?.takeIf { it.isNotEmpty() }?.let { modes ->
                    val seeded = modes.split(",").map { it.trim() }.filter { it in s.supportedModes }
                    if (seeded.isNotEmpty()) engines = seeded
                }
                workspaceRoot = s.settings["workspaceRoot"]?.takeIf { it.isNotEmpty() } ?: s.defaultFolder
                s.settings["username"]?.let { username = it }
                runDetectNow()
            }
            is SetupCall.Failed ->
                if (call.status == 401) onSignIn() else stateError = call.message.ifEmpty { SetupCopy.STATE_FAILED }
        }
    }

    fun runDetect() {
        scope.launch { runDetectNow() }
    }

    /** page.tsx :104-126 runDetect: best-effort; it pre-selects what it finds, never fighting a toggle. */
    internal suspend fun runDetectNow() {
        detecting = true
        try {
            val call = api.detect()
            if (call is SetupCall.Ok) {
                val found = call.value
                detected = found
                if (!enginesTouched) {
                    val merged = LinkedHashSet(engines)
                    for ((engine, det) in found) if (det.found) merged.add(engine)
                    engines = merged.toList()
                }
            }
        } finally {
            detecting = false
        }
    }

    /**
     * Forget everything, the operator's password first: the wizard was left (signed in on, or cancelled).
     * Nothing typed here outlives the wizard, in memory or anywhere else.
     */
    fun reset() {
        password = ""
        confirmPassword = ""
        showPassword = false
        username = ""
        step = 0
        state = null
        stateError = ""
        error = ""
        busy = false
        engines = emptyList()
        enginesTouched = false
        detected = emptyMap()
        detecting = false
        locators = emptyMap()
        locatorChecks = emptyMap()
        useBundled = false
        isolation = Isolation.Open
        workspaceRoot = ""
        finish = null
        closePicker()
    }

    // ---- steps ------------------------------------------------------------------------------

    val passwordOk: Boolean get() = forced("password") || (password.isNotEmpty() && password == confirmPassword)
    val operatorValid: Boolean get() = username.trim().isNotEmpty() && passwordOk
    private val realEngines: List<String> get() = engines.filter { it != "fake" }
    val enginesValid: Boolean
        get() = forced("headlessModes") ||
            (engines.isNotEmpty() && (useBundled || realEngines.all { detected[it]?.found == true || (locators[it] ?: "").trim().isNotEmpty() }))

    /** page.tsx :260-267: Step 4 and 5 are skippable, so no gate for either. */
    val canAdvance: Boolean
        get() = when (step) {
            1 -> operatorValid
            2 -> enginesValid
            3 -> workspaceRoot.isNotEmpty()
            else -> true
        }

    fun begin() {
        if (state != null) step = 1
    }

    fun next() {
        error = ""
        step += 1
    }

    fun back() {
        error = ""
        step = maxOf(0, step - 1)
    }

    fun retryLoad() {
        stateError = ""
        load()
    }

    fun toggleEngine(engine: String) {
        enginesTouched = true
        engines = if (engine in engines) engines - engine else engines + engine
    }

    fun setLocator(engine: String, value: String) {
        locators = locators + (engine to value)
        if (value.trim().isEmpty()) {
            locatorChecks = locatorChecks + (engine to null)
            return
        }
        scope.launch { validateLocator(engine, value) }
    }

    /** page.tsx :177-187: the located folder is checked as it is typed or picked; a failed check shows nothing. */
    internal suspend fun validateLocator(engine: String, value: String) {
        val call = api.validateEngineBinary(engine, value.trim())
        // A newer value for this engine owns the line.
        if (locators[engine] != value) return
        locatorChecks = locatorChecks + (engine to (call as? SetupCall.Ok)?.value)
    }

    fun pickWorkspace(path: String) {
        workspaceRoot = path
    }

    // ---- folder picker ----------------------------------------------------------------------

    fun openPicker(target: PickerTarget, from: String?) {
        picker = target
        pickerListing = null
        pickerError = null
        browse(from)
    }

    fun browse(path: String?) {
        val generation = ++browseGeneration
        pickerError = null
        scope.launch {
            val call = api.browse(path)
            if (generation != browseGeneration || picker == null) return@launch
            when (call) {
                is SetupCall.Ok -> pickerListing = call.value
                is SetupCall.Failed -> pickerError = call.message.ifEmpty { SetupCopy.BROWSE_FAILED }
            }
        }
    }

    fun closePicker() {
        picker = null
        pickerListing = null
        pickerError = null
        browseGeneration++
    }

    /** "Use this folder": the workspace root, or the located folder of the engine the picker was opened for. */
    fun choose(path: String) {
        when (val target = picker) {
            PickerTarget.Workspace -> pickWorkspace(path)
            is PickerTarget.Locate -> setLocator(target.engine, path)
            null -> Unit
        }
        closePicker()
    }

    // ---- apply ------------------------------------------------------------------------------

    /** page.tsx :189-216 apply: the settings the server is handed, built exactly as the page builds them. */
    internal fun settingsPayload(): JsonObject = buildJsonObject {
        put("username", JsonPrimitive(username.trim()))
        if (!forced("password") && password.isNotEmpty()) put("password", JsonPrimitive(password))
        if (!forced("headlessModes")) put("headlessModes", JsonPrimitive(engines.joinToString(",")))
        if (!forced("workspaceRoot") && workspaceRoot.isNotEmpty()) put("workspaceRoot", JsonPrimitive(workspaceRoot))
        // Own-CLI mode inherits the host environment wholesale; bundled mode is a factory-fresh
        // environment with an optional guided-isolation follow-up.
        put("useBundledHarnesses", JsonPrimitive(useBundled))
        put("shareHostConfig", JsonPrimitive(!useBundled))
        put("guidedIsolation", JsonPrimitive(useBundled && isolation == Isolation.Guided))
        if (!useBundled) {
            for ((engine, meta) in SetupEngines.meta) {
                val located = (locators[engine] ?: "").trim()
                if (engine in engines && located.isNotEmpty() && !forced(meta.overrideKey)) put(meta.overrideKey, JsonPrimitive(located))
            }
        }
    }

    fun apply() {
        if (busy) return
        busy = true
        error = ""
        scope.launch { applyNow() }
    }

    internal suspend fun applyNow() {
        busy = true
        error = ""
        try {
            when (val call = api.complete(settingsPayload())) {
                is SetupCall.Ok -> finish = call.value
                is SetupCall.Failed -> error = call.message.ifEmpty { SetupCopy.COMPLETE_FAILED }
            }
        } finally {
            busy = false
        }
    }

    // ---- restart ----------------------------------------------------------------------------

    /**
     * page.tsx :218-236: after a supervised restart, poll /healthz until the configured server is up, then
     * hand off to sign-in. The first look comes one interval after Configured appears.
     */
    suspend fun pollRestart() {
        if (finish?.automatic != true) return
        while (true) {
            delay(restartPollMs)
            if (api.configured()) {
                onSignIn()
                return
            }
        }
    }

    /** "Go to sign-in" (the manual restart's link, page.tsx :317). */
    fun goToSignIn() = onSignIn()
}
