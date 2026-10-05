package com.tether.app.client

import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * ta-23f (T8.1 slice 5): worktree isolation in the new-session composer — components/draft-composer.tsx
 * WorktreeSelect / WorktreeDetails (887c222 ~631-809), lib/draft-form.ts buildWorktreeCreateRequest
 * (~156-178), the readiness reasons (hooks/use-draft-composer.ts ~274-278) and the v98 wire shapes
 * (lib/protocol.ts WorktreeSourceInfo, `worktree-inspect` / `worktree-source`).
 *
 * ta-coik.11 (superseded by ta-m7ef): the deployed web 90fbb9f only showed a note when the inspected
 * config declared setup or scripts ([WorktreeCopy.setupNote]) and sent the create at once. Since tether
 * #241 (PROTOCOL 143) the server runs a checkout's hooks only on a matching consent: the composer asks
 * what THIS create would run (`worktree-inspect` with its `worktree` block, [WorktreeSourceInfo.setupPreview]),
 * shows it ([SetupConsentSteps]) and sends `create.setupConsent` ([DraftComposerModel]).
 */

/** draft-composer.tsx WorktreeSelect's values and words, verbatim. */
object WorktreeModes {
    const val LOCAL = "local"
    const val BRANCH_OFF = "branch-off"
    const val CHECKOUT_BRANCH = "checkout-branch"
    const val CHECKOUT_PR = "checkout-pr"

    /** WORKTREE_MODE_LABELS. */
    val LABELS: Map<String, String> = linkedMapOf(
        BRANCH_OFF to "New branch",
        CHECKOUT_BRANCH to "Existing branch",
        CHECKOUT_PR to "Pull request",
    )

    /** The select's rows, in the web's order (Local first and the default). */
    val OPTIONS: List<ControlOption> = listOf(
        ControlOption(LOCAL, "Local", "Work directly in the folder above — changes land on its current branch right away."),
        ControlOption(BRANCH_OFF, LABELS.getValue(BRANCH_OFF), "Cut a new branch in its own checkout. Starts from the remote's default branch unless you name another base."),
        ControlOption(CHECKOUT_BRANCH, LABELS.getValue(CHECKOUT_BRANCH), "Open a branch that already exists in its own checkout, so this session continues that work."),
        ControlOption(CHECKOUT_PR, LABELS.getValue(CHECKOUT_PR), "Fetch a pull request's head into its own checkout for review."),
    )

    /** One of the three isolation modes (never "local", which is `useWorktree: false`). */
    fun isMode(value: String): Boolean = value in LABELS

    /** The select's value for [form]: "local" while isolation is off, else the form's mode. */
    fun selected(form: JsObj): String =
        if (form["useWorktree"] == JsBool.TRUE) (form["worktreeMode"] as? JsStr)?.value?.takeIf(::isMode) ?: BRANCH_OFF else LOCAL

    /** The select. */
    fun control(form: JsObj): SelectControl = SelectControl(value = selected(form), options = OPTIONS)
}

/** One declared script or service (WorktreeSourceInfo.declaredScripts). */
data class WorktreeDeclaredScript(val name: String, val type: String, val port: Int?)

/**
 * lib/protocol.ts WorktreeSourceInfo: what the selected folder's repo offers, as the server answered
 * `worktree-inspect`. Parsed tolerantly and bounded ([parse]); every string is the repo's or the
 * server's, so it is untrusted text wherever it is drawn (branch names by the exact rule).
 */
data class WorktreeSourceInfo(
    val cwd: String = "",
    val isRepo: Boolean = false,
    val repoRoot: String? = null,
    val remote: String? = null,
    val remotes: List<String> = emptyList(),
    val currentBranch: String = "",
    val defaultBaseRef: String = "",
    val branches: List<String> = emptyList(),
    val configPresent: Boolean = false,
    val configWarnings: List<String> = emptyList(),
    val hasSetup: Boolean = false,
    val hasTeardown: Boolean = false,
    val declaredScripts: List<WorktreeDeclaredScript> = emptyList(),
    /**
     * v143 (ta-6t1): what a create would run from the ref the inspect's `worktree` block resolves, and the
     * consent that approves it. Null: absent (an older server), or not owner-grade, or not a repository.
     */
    val setupPreview: WorktreeSetupPreview? = null,
) {
    /** Redacted: paths and branch names stay out of logs. */
    override fun toString(): String =
        "WorktreeSourceInfo(isRepo=$isRepo, branches=${branches.size}, configPresent=$configPresent, hasSetup=$hasSetup, scripts=${declaredScripts.size})"

    companion object {
        /** The server sends at most 200 branches and 20 remotes; anything past these is dropped. */
        const val MAX_BRANCHES = 200
        const val MAX_REMOTES = 20
        const val MAX_WARNINGS = 24
        const val MAX_SCRIPTS = 64

        /** A ref, branch, remote or path longer than this is not one the server sends: dropped, never cut. */
        const val MAX_NAME = 256
        const val MAX_PATH = 4096

        private fun JsonObject.str(key: String, max: Int): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= max } ?: ""

        /** Only a JSON boolean counts (the string "true" is not one). */
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        private fun JsonObject.flag(key: String): Boolean = bool(key) == true

        private fun JsonObject.names(key: String, cap: Int): List<String> =
            ((this[key] as? JsonArray) ?: return emptyList()).asSequence()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .filter { it.isNotEmpty() && it.length <= MAX_NAME }
                .take(cap)
                .toList()

        /** The `info` of a `worktree-source` frame; anything malformed reads as its default. */
        fun parse(info: JsonObject): WorktreeSourceInfo = WorktreeSourceInfo(
            cwd = info.str("cwd", MAX_PATH),
            isRepo = info.flag("isRepo"),
            repoRoot = info.str("repoRoot", MAX_PATH).ifEmpty { null },
            remote = info.str("remote", MAX_NAME).ifEmpty { null },
            remotes = info.names("remotes", MAX_REMOTES),
            currentBranch = info.str("currentBranch", MAX_NAME),
            defaultBaseRef = info.str("defaultBaseRef", MAX_NAME),
            branches = info.names("branches", MAX_BRANCHES),
            configPresent = info.flag("configPresent"),
            configWarnings = ((info["configWarnings"] as? JsonArray) ?: JsonArray(emptyList())).asSequence()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .map(LabelText::hint)
                .filter { it.isNotEmpty() }
                .take(MAX_WARNINGS)
                .toList(),
            hasSetup = info.flag("hasSetup"),
            hasTeardown = info.flag("hasTeardown"),
            declaredScripts = ((info["declaredScripts"] as? JsonArray) ?: JsonArray(emptyList())).asSequence()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { o ->
                    val name = o.str("name", MAX_NAME).ifEmpty { return@mapNotNull null }
                    val port = (o["port"] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.intOrNull
                    WorktreeDeclaredScript(name, o.str("type", 16), port)
                }
                .take(MAX_SCRIPTS)
                .toList(),
            setupPreview = WorktreeSetupPreview.parse(info),
        )
    }
}

/** A `worktree-source` reply of the live socket: its info, its echo and the socket it came on. */
data class WorktreeSourceReply(val info: WorktreeSourceInfo, val requestId: String?, val linkEpoch: Long)

/**
 * The v98 isolation request the app sends: lib/draft-form.ts:156-184 buildWorktreeCreateRequest as it
 * is (ta-coik.4: the web's `Number.parseInt` rule for the pull request number, no limit of the app's
 * own; the server validates).
 */
object WorktreeDraft {
    /** draft-composer.tsx:756: the PR input keeps digits only (`replace(/[^0-9]/g, "")`). */
    fun prInput(text: String): String = text.filter { it in '0'..'9' }

    /**
     * The create's `worktree` block for [form] (a lib/draft-form.ts tree), or null: isolation off, or
     * the mode's required input missing (checkout-branch without a branch, checkout-pr without a
     * positive integer).
     */
    fun request(form: JsObj): JsObj? = DraftForm.buildWorktreeCreateRequest(form)

    /** use-draft-composer.ts:272-278 readiness, the isolation reason ("" = complete): the web's two words. */
    fun readiness(form: JsObj): String {
        if (form["useWorktree"] != JsBool.TRUE || request(form) != null) return ""
        return if (form["worktreeMode"] == JsStr(WorktreeModes.CHECKOUT_PR)) READINESS_NEED_PR else READINESS_NEED_BRANCH
    }
}

/** draft-composer.tsx WorktreeDetails' words. */
object WorktreeCopy {
    const val ROW_LABEL = "Worktree options"
    const val SELECT_NAME = "Workspace isolation"
    const val BASE = "Base"
    const val BRANCH = "Branch"
    const val PR = "Pull request"
    const val NEW_BRANCH = "New branch"
    const val NAME = "Name"
    const val BASE_PLACEHOLDER = "origin/main"
    const val BRANCH_PLACEHOLDER = "feature/existing"
    const val PR_PLACEHOLDER = "2186"
    const val NEW_BRANCH_PLACEHOLDER = "tether/<name>"
    const val NAME_PLACEHOLDER = "auto"

    /** `{form.cwd || "That folder"} is not a Git repository, so it cannot host an isolated session.` */
    fun notARepo(cwd: String): String = "${cwd.ifEmpty { "That folder" }} is not a Git repository, so it cannot host an isolated session."

    /**
     * The setup / scripts note, or null when the web draws none (no committed config, or one with
     * neither setup nor scripts). Verbatim, the web's sentence assembly included.
     */
    fun setupNote(source: WorktreeSourceInfo): String? {
        if (!source.configPresent || (!source.hasSetup && source.declaredScripts.isEmpty())) return null
        val n = source.declaredScripts.size
        val lead = if (source.hasSetup) "Runs this project's setup before the first turn" else "This project declares"
        val scripts = if (n > 0) "${if (source.hasSetup) "; " else " "}$n script${if (n == 1) "" else "s"} you can run in the session" else ""
        return "$lead$scripts."
    }
}
