package com.tether.app.client

import com.tether.app.protocol.fold.jsTrim
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
 * The setup confirmation (owner 2026-10-02; coordinator, owner-delegated, 2026-10-02, option A):
 * the server runs `tether.json` `worktree.setup` from the COMMITTED tree of the base the create
 * resolves (engines/worktree.mjs create: the pull request's head for checkout-pr, the named branch
 * for checkout-branch, the typed base for branch-off), while `worktree-inspect` reports `hasSetup`
 * for the remote's default branch only. So the app confirms whenever it cannot know what will run:
 * a pull request, an existing branch, and a new branch from a typed base other than the default; a
 * new branch from the default base confirms only when `hasSetup` says the setup will run. Local
 * never confirms. (ta-6t1, for the owner: the server could report setup for the chosen ref.)
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

        private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

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
        )
    }
}

/** A `worktree-source` reply of the live socket: its info, its echo and the socket it came on. */
data class WorktreeSourceReply(val info: WorktreeSourceInfo, val requestId: String?, val linkEpoch: Long)

/**
 * The v98 isolation request the app sends: lib/draft-form.ts buildWorktreeCreateRequest, with ONE
 * stricter rule (coordinator 2026-10-02): a pull request number must be a plain positive integer
 * after the web's trim (ASCII digits only) and at most the server's [PR_MAX]. The web's
 * `Number.parseInt` takes "42abc" as 42, "+7" as 7, "1e3" as 1 and 10^20 as itself (which the server
 * then refuses); none of those builds here, so the readiness reason blocks Send instead.
 */
object WorktreeDraft {
    /** lib/protocol-validate.mjs validateWorktreeRequest / engines/worktree.mjs: `prNumber <= 9_999_999`. */
    const val PR_MAX = 9_999_999

    /** The PR field keeps at most this many digits (the web keeps any number; the server tops out at 7). */
    const val PR_INPUT_MAX = 16

    /** Each typed ref / branch / slug: a longer edit is refused whole (never cut, so never another name). */
    const val FIELD_MAX = 256

    /** The form's PR number, or null when it is not a plain positive integer within [PR_MAX]. */
    fun prNumber(raw: String): Int? {
        val text = jsTrim(raw)
        if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
        val digits = text.trimStart('0')
        if (digits.isEmpty() || digits.length > PR_MAX.toString().length) return null
        val value = digits.toInt()
        return if (value in 1..PR_MAX) value else null
    }

    /** True when [raw] is digits only (after the trim) but past [PR_MAX]: readiness says so in its own words. */
    fun prTooLarge(raw: String): Boolean {
        val text = jsTrim(raw)
        if (text.isEmpty() || text.any { it !in '0'..'9' }) return false
        val digits = text.trimStart('0')
        return digits.length > PR_MAX.toString().length || (digits.isNotEmpty() && digits.toLong() > PR_MAX)
    }

    /** draft-composer.tsx: the PR input keeps digits only (`replace(/[^0-9]/g, "")`), here at most [PR_INPUT_MAX]. */
    fun prInput(text: String): String = text.filter { it in '0'..'9' }.take(PR_INPUT_MAX)

    /**
     * The create's `worktree` block for [form] (a lib/draft-form.ts tree), or null: isolation off, or
     * the mode's required input missing (checkout-branch without a branch, checkout-pr without a valid
     * number). Every other field is the web's builder's, key for key.
     */
    fun request(form: JsObj): JsObj? {
        if (form["useWorktree"] != JsBool.TRUE) return null
        if (form["worktreeMode"] == JsStr(WorktreeModes.CHECKOUT_PR) && prNumber((form["worktreePr"] as? JsStr)?.value.orEmpty()) == null) return null
        return DraftForm.buildWorktreeCreateRequest(form)
    }

    /**
     * use-draft-composer.ts readiness, the isolation reason ("" = complete): the web's two words, plus
     * the app's own for a number past the server's limit.
     */
    fun readiness(form: JsObj): String {
        if (form["useWorktree"] != JsBool.TRUE || request(form) != null) return ""
        if (form["worktreeMode"] != JsStr(WorktreeModes.CHECKOUT_PR)) return READINESS_NEED_BRANCH
        return if (prTooLarge((form["worktreePr"] as? JsStr)?.value.orEmpty())) READINESS_PR_TOO_LARGE else READINESS_NEED_PR
    }
}

/** ta-23f: a pull request number past the server's limit (the web would send it and be refused). */
const val READINESS_PR_TOO_LARGE = "That pull request number is too large: Tether accepts numbers up to 9999999."

/**
 * What the setup confirmation shows for one create: the isolation [mode] (and its words), what the
 * checkout is made from ([refLabel] / [ref]: the pull request number, the existing branch, the typed
 * base or the default base; null = the remote's default branch, not known yet), the new branch name
 * when one was typed, the folder, and whether the setup WILL run ([certain]: a new branch from the
 * default base whose inspected `tether.json` declares setup) or MAY run (it cannot be checked before
 * the create). [ref], [newBranch] and [cwd] are untrusted text: drawn by the exact rule.
 */
data class SetupConfirmation(
    val mode: String,
    val refLabel: String,
    val ref: String?,
    val newBranch: String?,
    val cwd: String,
    val certain: Boolean,
) {
    val modeLabel: String get() = WorktreeModes.LABELS[mode] ?: mode

    val title: String get() = if (certain) SETUP_TITLE_WILL else SETUP_TITLE_MAY

    /** The body line: will run, or may run and cannot be checked (on that pull request / branch). */
    val body: String get() = when {
        certain -> SETUP_BODY_WILL
        mode == WorktreeModes.CHECKOUT_PR -> SETUP_BODY_MAY_PR
        else -> SETUP_BODY_MAY_BRANCH
    }

    /** Redacted. */
    override fun toString(): String = "SetupConfirmation(mode=$mode, certain=$certain)"
}

const val SETUP_TITLE_WILL = "This project's setup will run"
const val SETUP_TITLE_MAY = "Setup may run on this host"
const val SETUP_BODY_WILL = "This project's setup will run on this host before the first turn."
const val SETUP_BODY_MAY_PR = "The setup committed on that pull request may run on this host before the first turn; it can't be checked beforehand."
const val SETUP_BODY_MAY_BRANCH = "The setup committed on that branch may run on this host before the first turn; it can't be checked beforehand."
const val SETUP_CONFIRM_ACTION = "Create session"

/** The confirmation's field names. */
const val SETUP_FIELD_ISOLATION = "Isolation"
const val SETUP_FIELD_BASE = "Base"
const val SETUP_FIELD_BRANCH = "Branch"
const val SETUP_FIELD_PR = "Pull request"
const val SETUP_FIELD_NEW_BRANCH = "New branch"
const val SETUP_FIELD_FOLDER = "Folder"

/** What [SetupConfirmation.ref] reads when it is the remote's default branch, not known yet. */
const val SETUP_DEFAULT_BASE = "The remote's default branch"

object WorktreeSetupGate {
    /**
     * The confirmation [form] needs before its create may be sent, or null when none: Local, an
     * incomplete isolation request (readiness blocks it anyway), or a new branch from the default
     * base whose inspected config declares no setup. [source] is the `worktree-source` that answered
     * THIS folder's inspect on THIS socket, or null when none has (then nothing is known, and a new
     * branch from the default base confirms too: fail closed).
     */
    fun confirmationFor(form: JsObj, source: WorktreeSourceInfo?): SetupConfirmation? {
        val request = WorktreeDraft.request(form) ?: return null
        val cwd = (form["cwd"] as? JsStr)?.value.orEmpty()
        val branch = (request["branch"] as? JsStr)?.value
        return when ((request["mode"] as? JsStr)?.value) {
            WorktreeModes.CHECKOUT_PR -> {
                val number = WorktreeDraft.prNumber((form["worktreePr"] as? JsStr)?.value.orEmpty()) ?: return null
                SetupConfirmation(WorktreeModes.CHECKOUT_PR, SETUP_FIELD_PR, "#$number", branch, cwd, certain = false)
            }
            WorktreeModes.CHECKOUT_BRANCH -> SetupConfirmation(WorktreeModes.CHECKOUT_BRANCH, SETUP_FIELD_BRANCH, branch, null, cwd, certain = false)
            WorktreeModes.BRANCH_OFF -> {
                val base = (request["baseRef"] as? JsStr)?.value
                val defaultBase = source?.defaultBaseRef?.takeIf { it.isNotEmpty() }
                when {
                    // A typed base other than the default: its committed config is not the inspected one.
                    base != null && base != defaultBase -> SetupConfirmation(WorktreeModes.BRANCH_OFF, SETUP_FIELD_BASE, base, branch, cwd, certain = false)
                    // Nothing inspected for this folder on this socket: nothing is known.
                    source == null -> SetupConfirmation(WorktreeModes.BRANCH_OFF, SETUP_FIELD_BASE, base, branch, cwd, certain = false)
                    source.hasSetup -> SetupConfirmation(WorktreeModes.BRANCH_OFF, SETUP_FIELD_BASE, base ?: defaultBase, branch, cwd, certain = true)
                    else -> null
                }
            }
            else -> null
        }
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
