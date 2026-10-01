package com.tether.app.ui.settings

import com.tether.app.client.LabelText
import com.tether.app.client.Profile
import com.tether.app.client.ProfileEdit
import com.tether.app.client.ProfileLimits
import com.tether.app.client.ProfileRunsEdit
import com.tether.app.client.ProvidersList
import com.tether.app.client.ProvidersPatch
import com.tether.app.client.ProvidersWrite
import com.tether.app.client.jsTrim

/**
 * ta-q6p: where the Custom providers editor sends its writes. The app's is the client
 * ([com.tether.app.client.TetherClient.setProviders]), which sends a write only on a socket opened
 * for [origin] and only when it passes [ProvidersPatch.refusal] against its newest list.
 */
fun interface ProvidersWriter {
    fun setProviders(write: ProvidersWrite, origin: String): Boolean

    /** No client (previews, a signed-out frame): nothing is ever sent. */
    object None : ProvidersWriter {
        override fun setProviders(write: ProvidersWrite, origin: String) = false
    }
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
 * landed applies to that broadcast and never undoes it.
 */
data class ProvidersBinding(
    val list: ProvidersList?,
    val origin: String?,
    val writer: ProvidersWriter = ProvidersWriter.None,
    /** The client's newest list, read when a write is built. Null: [list] is the newest (tests, previews). */
    val fresh: (() -> ProvidersList?)? = null,
) {
    /** The newest list of this binding's server, or null without one. */
    fun latest(): ProvidersList? = if (origin == null) null else fresh?.invoke() ?: list

    /** A plain edit, applied to the newest list and sent to this binding's server; false when nothing was sent. */
    fun send(edit: ProfileEdit): Boolean {
        val o = origin ?: return false
        val newest = latest()
        val write = ProvidersPatch.write(newest, edit) ?: return false
        return ProvidersPatch.refusal(write, newest) == null && writer.setProviders(write, o)
    }

    /** A command or home the user CONFIRMED, applied to the newest list at that moment. */
    fun sendConfirmed(edit: ProfileRunsEdit): Boolean {
        val o = origin ?: return false
        val newest = latest()
        val write = ProvidersPatch.confirmed(newest, edit) ?: return false
        return ProvidersPatch.refusal(write, newest) == null && writer.setProviders(write, o)
    }

    companion object {
        val None = ProvidersBinding(null, null)
    }
}

/**
 * ta-q6p: a profile's command or home waiting for its confirmation (owner decision 2026-10-01:
 * what the server runs is confirmed, the new value shown). [parts]: what will be sent (a command's
 * binary and arguments; a home is one part, none when cleared); [now]: what the profile runs now,
 * as the confirmation opened (it closes if that changes); [normalized]: typing was rewritten into
 * [parts] (trimmed, or split at spaces), which the confirmation says. Never saved state.
 */
data class ProfileRunsReview(
    val profileId: String,
    val name: String,
    val extends: String,
    val home: Boolean,
    val parts: List<String>,
    val now: List<String>,
    val normalized: Boolean,
) {
    val edit: ProfileRunsEdit
        get() = if (home) ProfileRunsEdit.Home(profileId, parts.firstOrNull().orEmpty()) else ProfileRunsEdit.Command(profileId, parts)

    override fun toString(): String = "ProfileRunsReview($profileId, home=$home)"
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
    fun reviewCommand(p: Profile, typed: String, shown: String): ProfileRunsReview? {
        if (typed == shown) return null
        val parts = ProvidersPatch.commandParts(typed)
        val now = p.command ?: emptyList()
        if (parts.joinToString(" ") == now.joinToString(" ") || !ProvidersPatch.commandFits(parts)) return null
        return ProfileRunsReview(p.id, name(p), p.extends, home = false, parts = parts, now = now, normalized = parts.joinToString(" ") != typed)
    }

    /** Done in the home field (:735): the trimmed value, when it differs from the profile's. */
    fun reviewHome(p: Profile, typed: String, shown: String): ProfileRunsReview? {
        if (typed == shown) return null
        val value = jsTrim(typed)
        val now = p.homeDir.orEmpty()
        if (value == now || value.length > ProfileLimits.HOME) return null
        return ProfileRunsReview(p.id, name(p), p.extends, home = true, parts = listOfNotNull(value.ifEmpty { null }), now = listOfNotNull(now.ifEmpty { null }), normalized = value != typed)
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
}
