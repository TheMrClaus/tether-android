package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.tree.JsObj

/**
 * ta-895: what one row of the New session picker names, exactly as it was drawn: the catalog row's
 * [key], its engine ([provider]) and the profile it runs on ([profileId]; null = the implicit
 * default profile, which for Claude is the host's default account). The picker hands it back on a
 * tap; the client resolves it again, under its lock, against the catalog the CURRENT socket
 * delivered ([NewSessionGuard.resolve]). It is never persisted.
 */
data class NewSessionChoice(val key: String, val provider: String, val profileId: String?) {
    /** ta-8cv r2 (security F3): redacted; a profile row's key IS its profile id. */
    override fun toString(): String = "NewSessionChoice(provider=$provider, profile=${if (profileId == null) "none" else "***"})"
}

/** ta-895: what became of a New session tap. */
enum class NewSessionResult {
    /** The `create` went out on the live socket. */
    Sent,

    /**
     * No live, handshaken socket (or the client is halted; ta-8cv: or the socket the draft was
     * composed on has been replaced): nothing was sent.
     */
    NotConnected,

    /** Drawn for another server than the socket's, or for none: nothing was sent. */
    NotLive,

    /**
     * The server's live catalog no longer offers the row as drawn (gone, changed engine or profile,
     * unavailable, still loading, ambiguous or malformed): nothing was sent, and in particular the
     * session was NOT created on the default profile instead.
     */
    NotOffered,
}

/** ta-895: one row of the New session picker, in the order the server listed it. */
data class NewSessionRow(
    val choice: NewSessionChoice,
    /** The catalog row; null for a base-provider row shown while no live catalog is in. */
    val entry: ProviderCatalogEntry?,
    /** The raw server label (cleaned where drawn), or null. */
    val label: String?,
    /** "ready" | "loading" | "error" | "unavailable" (anything else is drawn as "error"). */
    val status: String,
    /** The row creates a session when tapped (the web's submit readiness, use-draft-composer.ts). */
    val creatable: Boolean,
)

/**
 * ta-895: the New session picker's rules, as pure checks. The web lists the `providers-snapshot`
 * catalog (model-browser.tsx ModelBrowser, `entries.map(ProviderRow)`), whose rows the server builds
 * in lib/provider-catalog.mjs buildDescriptors: every ENABLED profile first (key = profileId,
 * provider = the profile's `extends` engine), then one implicit default row per non-acp engine
 * (key = provider id, no profileId; acp has no implicit row, its rows ARE its profiles). Create
 * submits `...(entry.profileId ? { profileId } : {})` (use-draft-composer.ts:339) and the server
 * re-validates it against its live providers.json (server.mjs createSession: unknown, disabled or
 * `extends` mismatch is refused).
 *
 * The app adds a client-side fail-closed check on the same identity: a profile id goes out only as
 * a row of the catalog the CURRENT socket delivered, matching what was drawn (key, engine and
 * profile all equal), unique, well formed, and in a state the web would submit. When no live
 * catalog is in, only a base provider's default row can be created, never a profile.
 */
object NewSessionGuard {

    /** protocol-validate.mjs LIMITS.ID_LENGTH: `create.profileId` / ProviderCatalogEntry.profileId. */
    const val PROFILE_ID_MAX = 128

    /** protocol-validate.mjs validateProviderCatalogEntry: `key` is bounded to ID_LENGTH * 2. */
    const val KEY_MAX = PROFILE_ID_MAX * 2

    /** lib/provider-catalog.mjs: acp has no implicit default row (its rows are its profiles). */
    private const val ACP = "acp"

    /**
     * The catalog rows as the server must have built them (lib/provider-catalog.mjs buildDescriptors,
     * protocol-validate.mjs validateProviderCatalogEntry). A profile row: a bounded non-empty
     * profileId equal to its key, `extends` absent or its engine. A default row: no profileId, key
     * equal to its engine, never acp. Anything else is drawn but never created.
     */
    fun wellFormed(entry: ProviderCatalogEntry): Boolean {
        if (entry.key.isEmpty() || entry.key.length > KEY_MAX || entry.provider.isEmpty()) return false
        if (entry.extends != null && entry.extends != entry.provider) return false
        val profileId = entry.profileId
        return if (profileId != null) {
            profileId.isNotEmpty() && profileId.length <= PROFILE_ID_MAX && profileId == entry.key
        } else {
            entry.key == entry.provider && entry.provider != ACP
        }
    }

    /**
     * use-draft-composer.ts readiness: an `unavailable` row is "Choose a provider first.", a
     * `loading` one "Models are still loading."; a `ready` row submits, and so does an `error` one
     * (the model list failed, the engine did not). An unknown status is never submitted.
     */
    fun submittable(status: String): Boolean = status == "ready" || status == "error"

    /**
     * The rows to draw. [liveCatalog] is the catalog the current socket delivered (null: none is in
     * yet), listed in the server's order. Without one, the base [providers] stand in as their
     * default rows (no profile; acp, which has none, left out), so a new session can still be
     * started on a default engine while the catalog loads. r2 (F3): a live catalog that is EMPTY
     * (the server offered nothing, or nothing it sent decoded) is what the server offers: no rows,
     * as the web draws "No models available", and no default stands in for it.
     */
    fun rows(liveCatalog: List<ProviderCatalogEntry>?, providers: List<ProviderInfo>): List<NewSessionRow> {
        val catalog = liveCatalog
        if (catalog == null) {
            return providers.filter { it.id.isNotEmpty() && it.id != ACP }.map { provider ->
                NewSessionRow(
                    choice = NewSessionChoice(provider.id, provider.id, null),
                    entry = null,
                    label = provider.label,
                    status = if (provider.available) "ready" else "unavailable",
                    creatable = provider.available,
                )
            }
        }
        val keyCounts = catalog.groupingBy { it.key }.eachCount()
        return catalog.map { entry ->
            NewSessionRow(
                choice = NewSessionChoice(entry.key, entry.provider, entry.profileId),
                entry = entry,
                label = entry.label,
                status = entry.status,
                creatable = submittable(entry.status) && wellFormed(entry) && keyCounts[entry.key] == 1,
            )
        }
    }

    /**
     * The catalog row [choice] starts on, or null when the server does not offer it now (fail
     * closed: never a create on another profile, and never the default profile in place of a
     * missing one).
     *
     * With a [liveCatalog] (the current socket's; r2: an empty one offers nothing): exactly one row
     * has the drawn key, and its engine and profile are the drawn ones, it is [wellFormed] and
     * [submittable]; that row. Without one (null: none is in yet): only a default row (no profile,
     * key = an engine id) of an available, non-acp base provider, as [baseEntry] (no profile).
     */
    fun resolveEntry(choice: NewSessionChoice, liveCatalog: List<ProviderCatalogEntry>?, providers: List<ProviderInfo>): ProviderCatalogEntry? {
        val catalog = liveCatalog
        if (catalog == null) {
            if (choice.profileId != null || choice.key != choice.provider || choice.provider == ACP) return null
            val provider = providers.firstOrNull { it.id == choice.provider } ?: return null
            if (!provider.available) return null
            return baseEntry(provider)
        }
        val matches = catalog.filter { it.key == choice.key }
        val entry = matches.singleOrNull() ?: return null
        if (entry.provider != choice.provider || entry.profileId != choice.profileId) return null
        if (!wellFormed(entry) || !submittable(entry.status)) return null
        return entry
    }

    /**
     * ta-8cv: the `create` for [request], or null when the server does not offer its row now
     * ([resolveEntry]). The frame is the web's, key for key ([CreateFrame.build]), on the row as the
     * live catalog has it (its engine and profile), never as the request claims them. ta-23f: null
     * too for an isolated create whose `worktree` block cannot be built (checkout-branch without a
     * branch, checkout-pr without a positive integer, as use-draft-composer.ts readiness refuses it):
     * the server would otherwise
     * make a default new branch from the remote's default base, and run that base's setup.
     */
    fun resolve(request: NewSessionRequest, liveCatalog: List<ProviderCatalogEntry>?, providers: List<ProviderInfo>): ClientMessage.Create? {
        val entry = resolveEntry(request.choice, liveCatalog, providers) ?: return null
        val frame = CreateFrame.build(request.form, entry, request.modified, request.requestId).copy(setupConsent = request.setupConsent)
        // ta-23f: an isolated create without its `worktree` block is never sent: the server would make a
        // default new branch (and run that base's setup) instead of the checkout the operator asked for.
        // The composer's readiness already refuses it; this is the last line.
        if (frame.useWorktree == true && frame.worktree == null) return null
        return frame
    }

    /** A base provider's implicit default row, standing in while no live catalog is in. */
    fun baseEntry(provider: ProviderInfo): ProviderCatalogEntry =
        ProviderCatalogEntry(provider.id, provider.id, if (provider.available) "ready" else "unavailable", emptyList(), label = provider.label)

    /**
     * ta-8cv: the rows the draft composer resolves its form against, as [rows] draws them: the live
     * catalog, or (none in yet) the base providers' default rows, acp left out.
     */
    fun draftEntries(liveCatalog: List<ProviderCatalogEntry>?, providers: List<ProviderInfo>): List<ProviderCatalogEntry> =
        liveCatalog ?: providers.filter { it.id.isNotEmpty() && it.id != ACP }.map(::baseEntry)
}

/**
 * ta-8cv: one new-session submit, as the draft composer composed it: the row drawn ([choice]), the
 * draft form and its `userModified` guard (lib/draft-form.ts shapes), the correlation token minted
 * for THIS attempt ([requestId], v76: the server echoes it on `created` / `error`), and the
 * [TetherClient.linkEpoch] the draft was composed on (the client refuses the create if that socket
 * has gone). Never persisted, never retried.
 */
data class NewSessionRequest(
    val choice: NewSessionChoice,
    val form: JsObj,
    val modified: JsObj,
    val requestId: String,
    val linkEpoch: Long,
    /**
     * ta-m7ef (v143): the consent to the worktree hooks this create resolves, the one the setup check
     * reported and the operator approved ("none" when it declared nothing); null for a local session.
     * Sent as `create.setupConsent` exactly as given.
     */
    val setupConsent: String? = null,
) {
    /** ta-8cv r2 (security F3): redacted (no profile id, folder or picks); the wire form is the client's. */
    override fun toString(): String = "NewSessionRequest(choice=$choice, requestId=$requestId, linkEpoch=$linkEpoch)"
}
