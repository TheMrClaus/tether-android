package com.tether.app.ui.settings

import com.tether.app.client.LabelText
import com.tether.app.client.Profile
import com.tether.app.client.ProfileEdit
import com.tether.app.client.ProfileLimits
import com.tether.app.client.ProfileRunsEdit
import com.tether.app.client.ProvidersList
import com.tether.app.client.ProvidersPatch
import com.tether.app.client.ProvidersWrite
import com.tether.app.client.ProvidersBuild
import com.tether.app.client.ProvidersInFlight
import com.tether.app.client.ProvidersRefusal
import com.tether.app.client.RunsSnapshot
import com.tether.app.client.RiskyEnvKeys
import com.tether.app.client.EnvChange
import com.tether.app.client.SecretText
import com.tether.app.client.jsTrim

/**
 * ta-q6p: where the Custom providers editor sends its writes. The app's is the client
 * ([com.tether.app.client.TetherClient.setProviders]), which sends a write only on a socket opened
 * for [origin], built from a list that came on that socket, with no other write in flight, and
 * only when it passes [ProvidersPatch.refusal] against its newest list. Null: sent.
 */
fun interface ProvidersWriter {
    fun setProviders(write: ProvidersWrite, origin: String): ProvidersRefusal?

    /** No client (previews, a signed-out frame): nothing is ever sent. */
    object None : ProvidersWriter {
        override fun setProviders(write: ProvidersWrite, origin: String) = ProvidersRefusal.NotConnected
    }
}

/** ta-q6p r2: what became of an editor action. */
sealed interface ProvidersSend {
    data object Sent : ProvidersSend
    data object NoChange : ProvidersSend
    data class Refused(val reason: ProvidersRefusal) : ProvidersSend
}

/**
 * ta-q6p: what the Custom providers editor draws and where its writes go, for ONE server
 * ([origin]). [list] is the last `providers` frame (null until the server replies). Nothing here
 * is persisted; the env values it holds live in the client's last frame, which the client drops on
 * a server switch, a sign-out and an auth-required state, and the editor draws nothing without an
 * [origin].
 *
 * Every write is built when it is sent, from [latest]: the client's NEWEST list ([fresh]), not the
 * composed one (which may be a frame behind), so an edit made while another client's broadcast
 * landed applies to that broadcast and never undoes it. r2: [inFlight] refuses a write while the
 * last one sent from here still waits for its broadcast (the client applies the same rule).
 */
data class ProvidersBinding(
    val list: ProvidersList?,
    val origin: String?,
    val writer: ProvidersWriter = ProvidersWriter.None,
    /** The client's newest list, read when a write is built. Null: [list] is the newest (tests, previews). */
    val fresh: (() -> ProvidersList?)? = null,
    val inFlight: ProvidersInFlight = ProvidersInFlight(),
) {
    /** The newest list of this binding's server, or null without one. */
    fun latest(): ProvidersList? = if (origin == null) null else fresh?.invoke() ?: list

    /** A plain edit, applied to the newest list and sent to this binding's server. */
    fun send(edit: ProfileEdit): ProvidersSend {
        val newest = latest()
        return deliver(ProvidersPatch.build(newest, edit), newest)
    }

    /** A change of what a profile runs the user CONFIRMED against [expectedNow], applied to the newest list at that moment. */
    fun sendConfirmed(edit: ProfileRunsEdit, expectedNow: RunsSnapshot): ProvidersSend {
        val newest = latest()
        return deliver(ProvidersPatch.confirmed(newest, edit, expectedNow), newest)
    }

    private fun deliver(build: ProvidersBuild, newest: ProvidersList?): ProvidersSend {
        val o = origin ?: return ProvidersSend.Refused(ProvidersRefusal.NotConnected)
        val write = when (build) {
            is ProvidersBuild.Ready -> build.write
            ProvidersBuild.NoChange -> return ProvidersSend.NoChange
            is ProvidersBuild.Refused -> return ProvidersSend.Refused(build.reason)
        }
        (ProvidersPatch.refusal(write, newest) ?: inFlight.refusal(newest))?.let { return ProvidersSend.Refused(it) }
        writer.setProviders(write, o)?.let { return ProvidersSend.Refused(it) }
        inFlight.sent(write, newest)
        return ProvidersSend.Sent
    }

    companion object {
        val None = ProvidersBinding(null, null)
    }
}

/**
 * ta-q6p: a change of what a profile runs, waiting for its confirmation (owner decisions
 * 2026-10-01). [snapshot]: what the profile runs as the confirmation opened (its engine, command,
 * home and risky env keys; the confirmation closes if that changes, and the write carries it, so
 * the server never gets a change built on another "Now"); [epoch]: the socket its list came on (a
 * reconnect closes it). Never saved state; [toString] prints no value.
 */
sealed interface ProfileReview {
    val profileId: String
    val name: String
    val snapshot: RunsSnapshot
    val epoch: Long
    val edit: ProfileRunsEdit
}

/**
 * A command or home: [parts] what will be sent (a home is one part, none when cleared), [now]
 * what runs now; [normalized]: typing was rewritten into [parts] (trimmed, or split at spaces).
 */
data class ProfileRunsReview(
    override val profileId: String,
    override val name: String,
    val extends: String,
    val home: Boolean,
    val parts: List<String>,
    val now: List<String>,
    val normalized: Boolean,
    override val snapshot: RunsSnapshot,
    override val epoch: Long = 0L,
) : ProfileReview {
    override val edit: ProfileRunsEdit
        get() = if (home) ProfileRunsEdit.Home(profileId, parts.firstOrNull().orEmpty()) else ProfileRunsEdit.Command(profileId, parts)

    override fun toString(): String = "ProfileRunsReview($profileId, home=$home)"
}

/** r2 (owner decision B): the engine, from [from] to [to], with the command and home the new engine will run. */
data class ExtendsReview(
    override val profileId: String,
    override val name: String,
    val from: String,
    val to: String,
    val command: List<String>,
    val home: String?,
    override val snapshot: RunsSnapshot,
    override val epoch: Long = 0L,
    /** r3: the profile's [RiskyEnvKeys] names (never values): the new engine reads them its own way. */
    val riskyKeys: List<String> = emptyList(),
) : ProfileReview {
    override val edit: ProfileRunsEdit get() = ProfileRunsEdit.Extends(profileId, to)
    override fun toString(): String = "ExtendsReview($profileId, $from -> $to)"
}

/** r2 (owner decision A): a risky env key's add, change, rename or remove; [nowValue] the key's value now (a secret). */
data class EnvReview(
    override val profileId: String,
    override val name: String,
    val change: EnvChange,
    val nowValue: SecretText?,
    override val snapshot: RunsSnapshot,
    override val epoch: Long = 0L,
    /** r3: run once the confirmed change was sent (the Add row clears its draft). */
    val afterSend: () -> Unit = {},
) : ProfileReview {
    override val edit: ProfileRunsEdit get() = ProfileRunsEdit.Env(profileId, change)
    override fun toString(): String = "EnvReview($profileId, ${change::class.simpleName}:${change.keys})"
}

/** The Custom providers section (settings-dialog.tsx 887c222 :621-1000 `ProfilesEditor`), the web's words verbatim. */
object ProfileRows {
    const val TITLE = "Custom providers"

    // :640 the caption, with its code spans.
    const val CAPTION_1 = "Declarative profiles extending the built-in engines — different credentials, binaries, or (later) model lists and tool allowlists per profile. Every entry needs an "
    const val CAPTION_2 = " (lowercase, hyphens), an "
    const val CAPTION_3 = " engine, and a label. "
    const val CAPTION_4 = " values are stored in plaintext in "
    const val CAPTION_5 = " (0600); prefer a dedicated home dir for credential isolation where the CLI reads its own config. Changes apply to future launches."

    const val EMPTY = "No custom providers configured — sessions run on the built-in engines."
    const val ADD = "Add profile"
    const val REMOVE = "Remove"
    const val LOADING = "Loading the custom providers…"

    /** A list the app cannot read exactly is shown, never written back (a whole-list write would rewrite what it lost). */
    const val READ_ONLY = "This list has an entry the app can't read exactly, so it can't be edited here. Edit custom providers from the web console."

    const val LABEL = "Label"
    const val LABEL_CAPTION = "Display name"
    const val ID = "ID"
    const val ID_CAPTION = "Stable registry key (lowercase letters, digits, hyphens)"
    const val EXTENDS = "Extends"
    const val EXTENDS_CAPTION = "Which built-in engine this profile runs on"
    const val COMMAND = "Command"
    const val COMMAND_CAPTION_1 = "Binary + arguments (space-separated), e.g. "
    const val COMMAND_EXAMPLE = "gemini --acp"
    const val COMMAND_CAPTION_2 = ". Claude uses the SDK-bundled CLI path — no command."
    const val HOME = "Home"
    const val HOME_ACP = "Required — the profile's dedicated credential home"
    const val HOME_OPTIONAL = "Optional — defaults to the engine's dedicated home"
    const val HOME_PLACEHOLDER = "/path/to/home"
    const val ENV = "Env"
    const val ENV_CAPTION = "Key/value overrides applied to the child environment (stored in plaintext in providers.json; prefer a dedicated home for credentials)"
    const val ENV_NAME_PLACEHOLDER = "NAME"
    const val ENV_VALUE_PLACEHOLDER = "value"
    const val ENV_NAME = "Environment variable name"
    const val ENV_NEW_NAME = "New environment variable name"
    const val ENV_NEW_VALUE = "New environment variable value"
    const val ADD_ENV = "Add"
    const val DROP_ENV = "Drop env prefixes"
    const val DROP_ENV_CAPTION_1 = "Comma-separated variable-name prefixes removed from the inherited environment (e.g. "
    const val DROP_ENV_EXAMPLE = "ANTHROPIC"
    const val DROP_ENV_CAPTION_2 = " strips your shell's ANTHROPIC_API_KEY)"
    const val TOOLS = "Disallowed tools"
    const val TOOLS_PLACEHOLDER = "WebSearch, Task"
    const val ORDER = "Order"
    const val ORDER_CAPTION = "New-session picker sorts ascending; empty sorts last"
    const val VERIFIED = "Verified through"
    const val VERIFIED_CAPTION_1 = "Approval-gate watermark — the build "
    const val VERIFIED_CAPTION_CODE = "npm run gate:profile-approval"
    const val VERIFIED_CAPTION_2 = " last passed at"
    const val VERIFIED_PLACEHOLDER = "0.43.0"
    const val MODELS = "Models"
    const val MODELS_CAPTION = "Replaces the engine's live-discovered list entirely for this profile. Empty = inherit live discovery + legacy catalog."
    const val ADDITIONAL = "Additional models"
    const val ADDITIONAL_CAPTION = "Merges with the live list by id — a matching id relabels the discovered model, a new id appends. Empty = no merge."
    const val MODEL_ID = "Model id"
    const val MODEL_LABEL_PLACEHOLDER = "Display name (defaults to the id)"
    const val MODEL_NEW_LABEL = "Display name for the new model"
    const val MODEL_DISCARD = "Discard the new model row"
    const val MODEL_NEW_DEFAULT = "New model is default (name the row first)"
    const val DEFAULT = "Default"
    const val ADD_MODEL = "Add model"

    /** :794 the disallowed tools' caption, by engine. */
    fun toolsCaption(extends: String) = "Comma-separated tool names denied before you are asked. " +
        if (extends == "claude") "UX policy, not a sandbox — the model could still act via another tool or path." else "Not yet supported on this engine — the field is disabled for $extends profiles."

    /** The card's name (:651 `profile.label || profile.id`), by the label rule. */
    fun name(p: Profile): String = LabelText.label(p.label.ifEmpty { p.id }).ifEmpty { LabelText.visibleValue(p.id) }

    /** A field's accessible name (the web's `aria-label`, `${profile.id} label`). */
    fun field(p: Profile, what: String) = "${LabelText.visibleValue(p.id)} $what"

    fun switchLabel(p: Profile) = "Enable ${name(p)}"
    fun valueLabel(key: String) = "Value for ${LabelText.visibleValue(key)}"
    fun removeLabel(what: String) = "Remove ${LabelText.visibleValue(what)}"
    fun defaultLabel(rowId: String) = "${LabelText.visibleValue(rowId)} is default"
    fun labelFor(rowId: String) = "Label for ${LabelText.visibleValue(rowId)}"

    /** `.provider-glyph`'s letter (:645). */
    fun glyph(extends: String) = extends.take(1).uppercase()

    // ---- the confirmation of what a profile runs ------------------------------------------------

    /** A command row's field text (:704): the parts joined by a space. */
    fun commandText(p: Profile) = (p.command ?: emptyList()).joinToString(" ")

    /**
     * Done in the command field: what to confirm, or null when there is nothing to send. As the
     * web's blur (:706-710): split at spaces, sent when the joined text differs from the profile's.
     * [shown] is what the field was filled with: an untouched field asks nothing.
     */
    fun reviewCommand(p: Profile, typed: String, shown: String, epoch: Long = 0L): ProfileRunsReview? {
        if (typed == shown) return null
        val parts = ProvidersPatch.commandParts(typed)
        val now = p.command ?: emptyList()
        if (parts.joinToString(" ") == now.joinToString(" ") || !ProvidersPatch.commandFits(parts)) return null
        return ProfileRunsReview(p.id, name(p), p.extends, home = false, parts = parts, now = now, normalized = parts.joinToString(" ") != typed, snapshot = RunsSnapshot.of(p), epoch = epoch)
    }

    /** Done in the home field (:735): the trimmed value, when it differs from the profile's. */
    fun reviewHome(p: Profile, typed: String, shown: String, epoch: Long = 0L): ProfileRunsReview? {
        if (typed == shown) return null
        val value = jsTrim(typed)
        val now = p.homeDir.orEmpty()
        if (value == now || value.length > ProfileLimits.HOME) return null
        return ProfileRunsReview(p.id, name(p), p.extends, home = true, parts = listOfNotNull(value.ifEmpty { null }), now = listOfNotNull(now.ifEmpty { null }), normalized = value != typed, snapshot = RunsSnapshot.of(p), epoch = epoch)
    }

    /** What the profile runs now, in the form a review names it (the confirmation closes when this changes). */
    fun runsNow(p: Profile, home: Boolean): List<String> = if (home) listOfNotNull(p.homeDir?.ifEmpty { null }) else p.command ?: emptyList()

    fun confirmTitle(r: ProfileRunsReview) = "Change the ${r.name} ${if (r.home) "home" else "command"}?"

    fun confirmBody(r: ProfileRunsReview): String = if (r.home) {
        "New sessions on this profile run with the home below (the CLI reads its config and credentials there). Hidden or direction-changing characters are shown as ⟨U+…⟩ marks."
    } else {
        "The server runs the command below for new sessions on this profile: the binary, then each argument on its own line. Hidden or direction-changing characters are shown as ⟨U+…⟩ marks."
    }

    /** The note under the new value when typing was rewritten into it. */
    fun normalizedNote(r: ProfileRunsReview): String = if (r.home) EngineRows.TRIMMED else SPLIT

    const val SPLIT = "Split at spaces and line breaks into the parts shown; extra spaces were dropped."

    fun emptyValue(r: ProfileRunsReview): String = when {
        !r.home -> "Empty — no command (the engine's own)"
        r.extends == "acp" -> "Empty — not set (an acp profile needs one)"
        else -> "Empty — the engine's dedicated home"
    }

    fun confirmAction(r: ProfileRunsReview) = if (r.home) "Change home" else "Change command"

    // ---- r2: the engine (owner decision B) ------------------------------------------------------

    /** Picking another engine: what to confirm (null for the same one). */
    fun reviewExtends(p: Profile, to: String, epoch: Long = 0L): ExtendsReview? {
        if (to == p.extends || to !in ProfileLimits.EXTENDS) return null
        return ExtendsReview(p.id, name(p), p.extends, to, p.command ?: emptyList(), p.homeDir, RunsSnapshot.of(p), epoch, p.envKeys.filter(RiskyEnvKeys::risky))
    }

    fun extendsTitle(r: ExtendsReview) = "Change the ${r.name} engine?"

    fun extendsBody(r: ExtendsReview): String = "The profile will run on another engine, with the command and home below. " +
        (if (r.to == "claude") "For Claude, the command's first part is the CLI path: the editor hides it, but the server still uses it. " else "") +
        "Hidden or direction-changing characters are shown as ⟨U+…⟩ marks."

    const val ENGINE_NEW = "Engine — change to"
    const val ENGINE_NOW = "Engine — now"
    const val ENGINE_COMMAND = "Command it will run"
    const val ENGINE_HOME = "Home it will use"
    const val ENGINE_ENV = "Variables it will read"
    const val ENGINE_ENV_NONE = "None that change what runs"
    const val ENGINE_ACTION = "Change engine"

    // ---- r2: the risky env keys (owner decision A) -----------------------------------------------

    /** An env change on [p]: a review when it touches a risky key, else null (it saves as before). */
    fun reviewEnv(p: Profile, change: EnvChange, epoch: Long = 0L, afterSend: () -> Unit = {}): EnvReview? {
        if (!change.risky) return null
        val nowKey = when (change) {
            is EnvChange.Change -> change.key
            is EnvChange.Remove -> change.key
            is EnvChange.Rename -> change.from
            is EnvChange.Add -> null
        }
        return EnvReview(p.id, name(p), change, nowKey?.let(p::envValue), RunsSnapshot.of(p), epoch, afterSend)
    }

    /** The action, in plain text (key names are drawn by the exact rule beside it). */
    fun envAction(c: EnvChange): String = when (c) {
        is EnvChange.Add -> "Add"
        is EnvChange.Change -> "Change the value of"
        is EnvChange.Rename -> "Rename"
        is EnvChange.Remove -> "Remove"
    }

    fun envTitle(r: EnvReview) = "Change what ${r.name} runs?"

    const val ENV_BODY = "This variable decides what the server runs for this profile, or where its CLI reads its config. Values stay hidden: reveal one to check it. Hidden or direction-changing characters are shown as ⟨U+…⟩ marks."
    const val ENV_ACTION_LABEL = "Change"
    const val CONFIRM_NEW_VALUE = "New value"
    const val CONFIRM_NOW_VALUE = "Value now"
    const val ENV_EMPTY_VALUE = "Empty"
    const val ENV_CONFIRM = "Confirm change"

    // ---- r2 (security F4): refused writes are never silent --------------------------------------

    const val NOT_SAVED_CHANGED = "Not saved: the list changed. Try again."
    const val NOT_SAVED_IN_FLIGHT = "Not saved: the last change is still being saved. Try again in a moment."
    const val NOT_SAVED_COLLISION = "Not saved: this profile already has a variable with that name."
    const val NOT_SAVED_BAD_NAME = "Not saved: use letters, digits and _ only (not starting with a digit)."
    const val NOT_SAVED_OFFLINE = "Not saved: not connected to the server. Try again."
    const val NOT_SAVED_INVALID = "Not saved: the server would refuse this value."
    const val CHANGED_WHILE_CONFIRMING = "Not saved: what this profile runs changed while you were confirming. Review it and try again."
    const val UNCONFIRMED = "The server hasn't confirmed the last change. Check the list before you edit again."

    fun notSaved(reason: ProvidersRefusal): String = when (reason) {
        ProvidersRefusal.InFlight -> NOT_SAVED_IN_FLIGHT
        ProvidersRefusal.Collision -> NOT_SAVED_COLLISION
        ProvidersRefusal.BadName -> NOT_SAVED_BAD_NAME
        ProvidersRefusal.NotConnected -> NOT_SAVED_OFFLINE
        ProvidersRefusal.Invalid -> NOT_SAVED_INVALID
        ProvidersRefusal.Changed -> CHANGED_WHILE_CONFIRMING
        ProvidersRefusal.NotWritable, ProvidersRefusal.Gone, ProvidersRefusal.Stale, ProvidersRefusal.NeedsConfirmation, ProvidersRefusal.Unconfirmed -> NOT_SAVED_CHANGED
    }

    /** A field's outcome for an editor action. */
    fun outcome(send: ProvidersSend): CommitOutcome = when (send) {
        ProvidersSend.Sent -> CommitOutcome.Sent
        ProvidersSend.NoChange -> CommitOutcome.Nothing
        is ProvidersSend.Refused -> CommitOutcome.Refused(notSaved(send.reason))
    }
}
