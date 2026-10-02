package com.tether.app.client

import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
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
 *
 * r2 (coordinator 2026-10-02, on the security review and the verifier's reproduction): the create
 * frame stays the web's, with no `remote`, so the server resolves a default base with remote
 * `origin` (server.mjs `worktreeRequest?.remote ?? "origin"`), and `defaultBaseRef` falls back to
 * local `HEAD` when origin has none. Inspect reads `hasSetup` at `origin` when the repo has one,
 * otherwise at the FIRST remote. So `hasSetup` describes what the create will read only when the
 * answer is a repo whose remote is `origin` and whose default is an `origin/…` tracking ref (never
 * `HEAD`, which moves with a local branch switch), `hasSetup` is a JSON boolean, and the config
 * read is not reported as failed. Anything else confirms as "may run" and names no inspected base.
 *
 * Residual (client-side control only; the fix is ta-6t1): the app does not re-inspect before the
 * create. Inspect answers from the refs the server LAST fetched and only then fetches in the
 * background, and an answer never expires, while the create fetches again before it resolves the
 * base. So an `origin/<default>` that moved upstream since the server's last fetch is the normal
 * case, not a rare race: the setup committed on the newer tip is what runs. A failed `git show`
 * at inspect is indistinguishable on the wire from "no tether.json" (887c222 readProjectConfig
 * answers `present: false` with no warnings for both), so a read that fails at inspect and succeeds
 * at create also runs unconfirmed. A modified client skips the confirmation entirely.
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
    /** False when the reply's `hasSetup` was not a JSON boolean: then nothing is known (the gate fails closed). */
    val setupKnown: Boolean = true,
    /**
     * r2: false when the config read cannot be taken at its word: `configPresent` is not a JSON
     * boolean, or it is not true yet the reply carries `configWarnings` (a read reported as failed).
     * 887c222 never sends the latter (a failed `git show` reads as no file); a server that reports
     * one gets the gate failing closed.
     */
    val configKnown: Boolean = true,
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
            setupKnown = info.bool("hasSetup") != null,
            configKnown = info.bool("configPresent").let { present ->
                present != null && (present || (info["configWarnings"] as? JsonArray).isNullOrEmpty())
            },
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
    /** The mode's words; a mode the app does not know is never drawn as its raw value. */
    val modeLabel: String get() = WorktreeModes.LABELS[mode] ?: SETUP_MODE_UNKNOWN

    val title: String get() = if (certain) SETUP_TITLE_WILL else SETUP_TITLE_MAY

    /** The body line: will run, or may run and cannot be checked (on that pull request / branch / the default base). */
    val body: String get() = when {
        certain -> SETUP_BODY_WILL
        mode == WorktreeModes.CHECKOUT_PR -> SETUP_BODY_MAY_PR
        ref == null -> SETUP_BODY_MAY_DEFAULT
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

/** r2: a new branch from the default base the app cannot name (the server resolves it at create). */
const val SETUP_BODY_MAY_DEFAULT = "The setup committed on the default base may run on this host before the first turn; it can't be checked beforehand."
const val SETUP_CONFIRM_ACTION = "Create session"

/** r2: the Isolation field for a mode the app does not know (the server refuses one; never drawn raw). */
const val SETUP_MODE_UNKNOWN = "Unrecognised mode"

/** The confirmation's field names. */
const val SETUP_FIELD_ISOLATION = "Isolation"
const val SETUP_FIELD_BASE = "Base"
const val SETUP_FIELD_BRANCH = "Branch"
const val SETUP_FIELD_PR = "Pull request"
const val SETUP_FIELD_NEW_BRANCH = "New branch"
const val SETUP_FIELD_FOLDER = "Folder"

/**
 * What [SetupConfirmation.ref] reads when the frame names no base and the app cannot name the one
 * the server will resolve (r2: neutral, since that is origin's default branch or local HEAD).
 */
const val SETUP_DEFAULT_BASE = "The default base"

object WorktreeSetupGate {
    /** The remote the create resolves its default base with when the frame names none (server.mjs). */
    const val CREATE_REMOTE = "origin"

    /**
     * True when [source]'s `hasSetup` was read at the very ref a frame naming no remote and no base
     * makes the create resolve (r2): a repo, remote `origin`, a default that is an `origin/…`
     * tracking ref (so never `HEAD`, which moves with a local branch switch), a JSON-boolean
     * `hasSetup` and a config read not reported as failed.
     */
    fun predictsDefaultBase(source: WorktreeSourceInfo?): Boolean {
        if (source == null || !source.isRepo) return false
        if (source.remote != CREATE_REMOTE) return false
        if (!source.defaultBaseRef.startsWith("$CREATE_REMOTE/")) return false
        if (!source.setupKnown) return false
        if (!source.configKnown) return false
        return true
    }

    /**
     * The confirmation [form] needs before its create may be sent, or null when none: Local, an
     * incomplete isolation request (readiness blocks it anyway), or a new branch from the default
     * base when [predictsDefaultBase] holds and the inspected config declares no setup. [source] is
     * the `worktree-source` that answered THIS folder's inspect on THIS socket, or null when none has.
     * Everything else confirms; a mode the app does not know confirms as "may run" too.
     */
    fun confirmationFor(form: JsObj, source: WorktreeSourceInfo?): SetupConfirmation? {
        val request = WorktreeDraft.request(form) ?: return null
        val cwd = (form["cwd"] as? JsStr)?.value.orEmpty()
        val branch = (request["branch"] as? JsStr)?.value
        val base = (request["baseRef"] as? JsStr)?.value
        return when (val mode = (request["mode"] as? JsStr)?.value) {
            WorktreeModes.CHECKOUT_PR -> {
                val number = (request["prNumber"] as? JsNum)?.value ?: return null
                SetupConfirmation(WorktreeModes.CHECKOUT_PR, SETUP_FIELD_PR, "#${numberToString(number)}", branch, cwd, certain = false)
            }
            WorktreeModes.CHECKOUT_BRANCH -> SetupConfirmation(WorktreeModes.CHECKOUT_BRANCH, SETUP_FIELD_BRANCH, branch, null, cwd, certain = false)
            WorktreeModes.BRANCH_OFF -> {
                val defaultBase = source?.defaultBaseRef?.takeIf { it.isNotEmpty() }
                // The ref shown is only ever the typed one (what the frame names) or, when the
                // inspected default is the create's, that default; otherwise the neutral words.
                val may = SetupConfirmation(WorktreeModes.BRANCH_OFF, SETUP_FIELD_BASE, base, branch, cwd, certain = false)
                when {
                    // A typed base other than the default: its committed config is not the inspected one.
                    base != null && base != defaultBase -> may
                    // The inspected default is not provably the one the create resolves (or nothing was inspected).
                    !predictsDefaultBase(source) -> may
                    source!!.hasSetup -> SetupConfirmation(WorktreeModes.BRANCH_OFF, SETUP_FIELD_BASE, base ?: defaultBase, branch, cwd, certain = true)
                    else -> null
                }
            }
            // Not a mode the app offers: the server refuses it, but nothing here can say what it would run.
            else -> SetupConfirmation(mode.orEmpty(), SETUP_FIELD_BASE, base, branch, cwd, certain = false)
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
