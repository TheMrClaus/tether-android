package com.tether.app.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * ta-m7ef (tether PR #241, PROTOCOL 143): server-enforced worktree setup / teardown consent.
 * The pure core of the app's side, a port of tether 1bf4a465:
 *  - lib/protocol.ts WorktreeSetupPreview (parsed tolerantly, fail closed);
 *  - lib/draft-form.ts setupConsentStep, scheduleConsentStep, setupCheckErrorApplies
 *    (the teardown half is TeardownConsent.kt; the hidden-character rule is :core:data HiddenCharacters).
 *
 * Everything repo-controlled here (a command, a port script path, a commit, a ref) is untrusted
 * text: it is drawn as plain text only, with every hidden character made visible. A list or string
 * past the bounds below is not one the server sends, so the preview is refused rather than cut
 * (a truncated command would not be the one the digest covers).
 */

/** The consent token for "nothing to run" (lib/worktree-setup-consent.mjs SETUP_CONSENT_NONE). */
const val SETUP_CONSENT_NONE: String = "none"

internal val SETUP_DIGEST_RE = Regex("^sha256:[0-9a-f]{64}$")

/** True for a value the wire accepts as a consent (`isSetupConsentValue`): "none" or `sha256:<64 hex>`. */
fun isSetupConsentValue(value: String?): Boolean = value == SETUP_CONSENT_NONE || (value != null && SETUP_DIGEST_RE.matches(value))

/** draft-form.ts: the web's words for a check that could not be read. */
const val SETUP_CHECK_FAILED_COPY: String = "Tether could not check this project's setup. Try again."

/** draft-form.ts teardownConsentStep's words. */
const val TEARDOWN_CHECK_FAILED_COPY: String = "Tether could not check this session's teardown."
const val TEARDOWN_TAMPERED_COPY: String = "This checkout's .git file was changed, so its teardown will not run."

private const val MAX_COMMANDS = 64
private const val MAX_COMMAND_CHARS = 16_384
private const val MAX_TEXT = 4096

internal fun JsonObject.text(key: String, max: Int = MAX_TEXT): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= max }

internal fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull

/** A string[] within the bounds, else null (absent, not an array, a non-string element, or past a bound). */
internal fun JsonObject.commandList(key: String): List<String>? {
    val array = this[key] as? JsonArray ?: return null
    if (array.size > MAX_COMMANDS) return null
    val out = ArrayList<String>(array.size)
    for (element in array) {
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (value.length > MAX_COMMAND_CHARS) return null
        out += value
    }
    return out
}

/**
 * lib/protocol.ts WorktreeSetupPreview: the hooks a create would run from the ref it resolves, and the
 * consent that approves them. [commands] / [teardown] are null when the server's list is not a bounded
 * string array (the step then refuses). [portScriptWellFormed] is false unless `portScript` is a string or
 * an explicit null (the web refuses an absent one).
 */
data class WorktreeSetupPreview(
    val mode: String,
    val remote: String,
    val baseRef: String?,
    val commit: String?,
    val commands: List<String>?,
    val teardown: List<String>?,
    val portScript: String?,
    val portScriptWellFormed: Boolean,
    val portScriptSha256: String?,
    val hiddenCharacters: Boolean,
    val digest: String?,
    val consent: String?,
    val scheduleConsent: String?,
    val error: String?,
) {
    /** Redacted: commands and refs stay out of logs. */
    override fun toString(): String = "WorktreeSetupPreview(mode=$mode, commands=${commands?.size}, teardown=${teardown?.size}, error=${error != null})"

    companion object {
        /** `info.setupPreview`: null when absent, null or not an object (a non-owner socket, or an older server). */
        fun parse(info: JsonObject): WorktreeSetupPreview? {
            val o = info["setupPreview"] as? JsonObject ?: return null
            val portScript = o["portScript"]
            return WorktreeSetupPreview(
                mode = o.text("mode", 32).orEmpty(),
                remote = o.text("remote", 256).orEmpty(),
                baseRef = o.text("baseRef"),
                commit = o.text("commit", 128),
                commands = o.commandList("commands"),
                teardown = o.commandList("teardown"),
                portScript = (portScript as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= MAX_COMMAND_CHARS },
                portScriptWellFormed = portScript is JsonNull || ((portScript as? JsonPrimitive)?.isString == true && (portScript as JsonPrimitive).content.length <= MAX_COMMAND_CHARS),
                portScriptSha256 = o.text("portScriptSha256", 128),
                hiddenCharacters = o.flag("hiddenCharacters") == true,
                digest = o.text("digest", 128),
                consent = o.text("consent", 128),
                scheduleConsent = o.text("scheduleConsent", 128),
                error = o.text("error", 2048),
            )
        }
    }
}

/** draft-form.ts SetupConsentStep. */
sealed interface SetupConsentStep {
    /** The ref declares no setup/teardown: create now with `setupConsent: "none"`. */
    data class Create(val setupConsent: String = SETUP_CONSENT_NONE) : SetupConsentStep

    /** It declares hooks: show them and create with [consent] only once the operator approves. */
    data class Confirm(val preview: SetupApproval) : SetupConsentStep

    /** The intent did not resolve: say why, create nothing. */
    data class Refuse(val message: String) : SetupConsentStep
}

/** What a create's setup confirmation shows: a validated [WorktreeSetupPreview], its consent and commit. */
data class SetupApproval(
    val mode: String,
    val baseRef: String,
    val commit: String,
    val commands: List<String>,
    val teardown: List<String>,
    val portScript: String?,
    val portScriptSha256: String?,
    val hiddenCharacters: Boolean,
    /** EXACTLY what to send as `create.setupConsent`. */
    val consent: String,
    /** EXACTLY what a schedule save sends (only on a schedule step). */
    val scheduleConsent: String? = null,
) {
    override fun toString(): String = "SetupApproval(mode=$mode, commands=${commands.size}, teardown=${teardown.size})"
}

/** draft-form.ts ScheduleConsentStep. */
sealed interface ScheduleConsentStep {
    data class Save(val setupConsent: String = SETUP_CONSENT_NONE) : ScheduleConsentStep
    data class Confirm(val preview: SetupApproval) : ScheduleConsentStep
    data class Refuse(val message: String) : ScheduleConsentStep
}

object SetupConsentSteps {
    /** True for a list that is malformed (null) or has entries: a "none" consent never carries either. */
    internal fun nonEmpty(list: List<String>?): Boolean = list == null || list.isNotEmpty()

    /** lib/draft-form.ts setupConsentStep. [info] is the `worktree-source` answer to this create's intent inspect. */
    fun create(info: WorktreeSourceInfo?): SetupConsentStep {
        if (info == null || !info.isRepo) return SetupConsentStep.Refuse("That folder is not a Git repository, so it cannot host an isolated session.")
        val p = info.setupPreview ?: return SetupConsentStep.Refuse(SETUP_CHECK_FAILED_COPY)
        if (!p.error.isNullOrEmpty()) return SetupConsentStep.Refuse(p.error)
        if (p.consent == SETUP_CONSENT_NONE) {
            // "none" with commands listed would be a server bug; never wave it through.
            if (nonEmpty(p.commands) || nonEmpty(p.teardown) || !p.portScript.isNullOrEmpty()) return SetupConsentStep.Refuse(SETUP_CHECK_FAILED_COPY)
            return SetupConsentStep.Create()
        }
        val consent = p.consent
        val commit = p.commit
        val baseRef = p.baseRef
        val commands = p.commands
        val teardown = p.teardown
        if (consent == null || !SETUP_DIGEST_RE.matches(consent) || consent != p.digest || commit == null || baseRef == null ||
            commands == null || teardown == null || !p.portScriptWellFormed
        ) {
            return SetupConsentStep.Refuse(SETUP_CHECK_FAILED_COPY)
        }
        return SetupConsentStep.Confirm(
            SetupApproval(p.mode, baseRef, commit, commands, teardown, p.portScript, p.portScriptSha256, p.hiddenCharacters, consent),
        )
    }

    /** lib/draft-form.ts scheduleConsentStep: the answer to the schedule save's setup check. */
    fun schedule(info: WorktreeSourceInfo?): ScheduleConsentStep {
        if (info == null || !info.isRepo) return ScheduleConsentStep.Refuse("That folder is not a Git repository, so an isolated schedule cannot run there.")
        val p = info.setupPreview ?: return ScheduleConsentStep.Refuse(SETUP_CHECK_FAILED_COPY)
        if (!p.error.isNullOrEmpty()) return ScheduleConsentStep.Refuse(p.error)
        if (p.scheduleConsent == SETUP_CONSENT_NONE) {
            if (nonEmpty(p.commands) || !p.portScript.isNullOrEmpty()) return ScheduleConsentStep.Refuse(SETUP_CHECK_FAILED_COPY)
            return ScheduleConsentStep.Save()
        }
        val consent = p.scheduleConsent
        val commands = p.commands
        if (consent == null || !SETUP_DIGEST_RE.matches(consent) || commands == null) return ScheduleConsentStep.Refuse(SETUP_CHECK_FAILED_COPY)
        return ScheduleConsentStep.Confirm(
            SetupApproval(
                mode = p.mode,
                baseRef = p.baseRef.orEmpty(),
                commit = p.commit.orEmpty(),
                commands = commands,
                teardown = p.teardown.orEmpty(),
                portScript = p.portScript,
                portScriptSha256 = p.portScriptSha256,
                hiddenCharacters = p.hiddenCharacters,
                consent = consent,
                scheduleConsent = consent,
            ),
        )
    }

    /**
     * lib/draft-form.ts setupCheckErrorApplies: does an `error` frame end the check in flight? While a check
     * is pending, any NEWER error with no requestId (a validator refusal is thrown before the server reads
     * it) or with the inspect's own ends it. An error echoing another request does not.
     */
    fun errorEndsCheck(checking: Boolean, inspectRequestId: String, errorSeqAtSend: Long, error: CreateErrorReply): Boolean {
        if (!checking) return false
        if (error.message.isEmpty() || error.seq <= errorSeqAtSend) return false
        return error.requestId == null || error.requestId == inspectRequestId
    }
}
