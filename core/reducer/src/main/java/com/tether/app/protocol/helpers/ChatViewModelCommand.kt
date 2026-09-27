package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsValue

/**
 * T2.2: the `/model <arg>` rule inlined in components/chat-view.tsx (ChatView, v128), extracted
 * verbatim like the exporter does. On a non-codex session with a non-empty arg: [resolveModelArg]
 * against the session's model list applies the match; otherwise, if [looksLikeModelId], the arg is
 * applied VERBATIM (free-text passthrough — setModel is unvalidated, the CLI is the validator);
 * otherwise the composer flashes a refusal. Bare `/model` falls through to the CLI.
 */
object ChatViewModelCommand {

    // components/chat-view.tsx:195
    val TETHER_NATIVE_COMMANDS: Set<String> = setOf("model")

    // components/chat-view.tsx:2959 — exact value, then exact displayName, then substring of either
    // (case-insensitive on the trimmed arg); null when nothing matches.
    fun resolveModelArg(arg: String, models: JsArr): JsValue? {
        val q = jsTrim(arg).lowercase()
        if (q.isEmpty()) return null
        fun value(model: JsValue) = jsToString(model["value"]).lowercase()
        fun displayName(model: JsValue) = jsToString(model["displayName"]).lowercase()
        return models.firstOrNull { value(it) == q }
            ?: models.firstOrNull { displayName(it) == q }
            ?: models.firstOrNull { value(it).contains(q) || displayName(it).contains(q) }
    }

    // components/chat-view.tsx:2976
    val LOOKS_LIKE_MODEL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{1,63}\\z")

    fun looksLikeModelId(arg: String): Boolean = LOOKS_LIKE_MODEL_ID.containsMatchIn(arg)
}
