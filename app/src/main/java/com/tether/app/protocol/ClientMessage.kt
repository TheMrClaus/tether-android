package com.tether.app.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    /**
     * Attachments ride an idle send only (v15, server-side: they are never
     * queued). [data] is base64 without a data: URI prefix. Null = no
     * attachments; the field is then omitted from the frame entirely.
     */
    data class Send(
        val sessionId: String,
        val text: String,
        val idempotencyKey: String,
        val attachments: List<Attachment>? = null,
    ) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "send")
            put("sessionId", sessionId)
            put("text", text)
            put("idempotencyKey", idempotencyKey)
            if (!attachments.isNullOrEmpty()) {
                putJsonArray("attachments") {
                    for (att in attachments) {
                        add(buildJsonObject {
                            put("name", att.name)
                            put("mediaType", att.mediaType)
                            put("data", att.data)
                        })
                    }
                }
            }
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

    /**
     * Claude only: switch the session's model ("" or "default" clears to the
     * CLI default). No direct reply: the ack is the `session` broadcast
     * carrying session.model, and the frame is silently dropped for
     * non-claude sessions — never wait on feedback for them.
     */
    data class SetModel(val sessionId: String, val model: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "set-model")
            put("sessionId", sessionId)
            put("model", model)
        }
    }

    /** Ask for the session's available models + slash-command list (reply: session-controls). */
    data class SessionControlsRequest(val sessionId: String) : ClientMessage {
        override fun toJsonObject() = buildJsonObject {
            put("type", "session-controls")
            put("sessionId", sessionId)
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

/**
 * A file/image carried by [ClientMessage.Send] (v15): [data] is base64 bytes,
 * no data: URI prefix. @Serializable only so PendingRecord can carry it; it is
 * never persisted (attachment records are excluded from the durable store).
 */
@Serializable
data class Attachment(val name: String, val mediaType: String, val data: String)
