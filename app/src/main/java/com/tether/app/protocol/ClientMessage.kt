package com.tether.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Client -> server frames (specs/protocol-spec.md §3). Serialized by hand via
 * buildJsonObject so field names are EXACTLY what lib/protocol-validate.mjs
 * accepts — optional fields are omitted entirely, never sent as null.
 */
sealed interface ClientMessage {
    fun toJsonObject(): JsonObject

    fun encode(): String = TetherJson.encodeToString(JsonObject.serializer(), toJsonObject())

    data class Hello(val protocolVersion: Int = PROTOCOL_VERSION) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "hello")
            put("protocolVersion", protocolVersion)
        }
    }

    data class Create(
        val provider: String,
        val cwd: String? = null,
        val name: String? = null,
        val permissionMode: String? = null,
        val sandboxPolicy: String? = null,
        val useWorktree: Boolean? = null,
    ) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "create")
            put("provider", provider)
            if (cwd != null) put("cwd", cwd)
            if (name != null) put("name", name)
            if (permissionMode != null) put("permissionMode", permissionMode)
            if (sandboxPolicy != null) put("sandboxPolicy", sandboxPolicy)
            if (useWorktree != null) put("useWorktree", useWorktree)
        }
    }

    data class Resume(val historyId: String, val cwd: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "resume")
            put("historyId", historyId)
            put("cwd", cwd)
        }
    }

    data class Discover(val cwd: String, val lastSeen: Map<String, Long>? = null) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "discover")
            put("cwd", cwd)
            if (lastSeen != null) {
                putJsonObject("lastSeen") {
                    for ((k, v) in lastSeen) put(k, JsonPrimitive(v))
                }
            }
        }
    }

    data class Browse(val cwd: String? = null) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "browse")
            if (cwd != null) put("cwd", cwd)
        }
    }

    data class Attach(val sessionId: String, val afterSeq: Long? = null) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "attach")
            put("sessionId", sessionId)
            if (afterSeq != null) put("afterSeq", afterSeq)
        }
    }

    /** Attachments are deliberately unsupported in v1 Android (see §5.6 note). */
    data class Send(val sessionId: String, val text: String, val idempotencyKey: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "send")
            put("sessionId", sessionId)
            put("text", text)
            put("idempotencyKey", idempotencyKey)
        }
    }

    data class QueueAdd(val sessionId: String, val queueId: String, val text: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "queue-add")
            put("sessionId", sessionId)
            put("queueId", queueId)
            put("text", text)
        }
    }

    data class QueueEdit(val sessionId: String, val queueId: String, val text: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "queue-edit")
            put("sessionId", sessionId)
            put("queueId", queueId)
            put("text", text)
        }
    }

    data class QueueRemove(val sessionId: String, val queueId: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "queue-remove")
            put("sessionId", sessionId)
            put("queueId", queueId)
        }
    }

    data class Interrupt(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "interrupt")
            put("sessionId", sessionId)
        }
    }

    /**
     * Exactly one of [choiceId] | [decision] must be non-null
     * (protocol-validate rejects both/neither). grantedPermissions is
     * deferred (v1 Android sends plain choices only).
     */
    data class Approval(
        val sessionId: String,
        val requestId: String,
        val choiceId: String? = null,
        val decision: String? = null,
    ) : ClientMessage {
        init {
            require((choiceId != null) != (decision != null)) {
                "approval must carry exactly one of choiceId or decision"
            }
        }

        override fun toJsonObject() = buildJsonObject {
            put("type", "approval")
            put("sessionId", sessionId)
            put("requestId", requestId)
            if (choiceId != null) put("choiceId", choiceId)
            if (decision != null) put("decision", decision)
        }
    }

    /**
     * Answer a parked AskUserQuestion. Wire nesting is
     * `answers: { answers: { "<question text>": "<label(s)>" }, response? }`.
     */
    data class Question(
        val sessionId: String,
        val requestId: String,
        val answers: Map<String, String>,
        val response: String? = null,
    ) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "question")
            put("sessionId", sessionId)
            put("requestId", requestId)
            putJsonObject("answers") {
                putJsonObject("answers") {
                    for ((k, v) in answers) put(k, JsonPrimitive(v))
                }
                if (response != null) put("response", response)
            }
        }
    }

    data class SetMode(val sessionId: String, val permissionMode: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "set-mode")
            put("sessionId", sessionId)
            put("permissionMode", permissionMode)
        }
    }

    data class Pin(val sessionId: String, val pinned: Boolean) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "pin")
            put("sessionId", sessionId)
            put("pinned", pinned)
        }
    }

    data class Rename(val sessionId: String, val name: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "rename")
            put("sessionId", sessionId)
            put("name", name)
        }
    }

    data class Archive(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "archive")
            put("sessionId", sessionId)
        }
    }

    data class Kill(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "kill")
            put("sessionId", sessionId)
        }
    }
}
