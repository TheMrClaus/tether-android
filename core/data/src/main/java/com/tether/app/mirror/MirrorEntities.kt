package com.tether.app.mirror

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * T13.1: mirror schema v1 (SYNC_DESIGN §2.2). Columns named `blob`, `state` and `payload`
 * hold AES-GCM blobs sealed by [MirrorCipher] (§8.5); everything else is clear index data
 * (ids, seq, event type, timestamps, flags), which leaks activity metadata only.
 *
 * Plain classes rather than data classes: a ByteArray has identity equality, so a generated
 * equals() would be misleading.
 */

/** One AgentSession row, upserted from `ready`, `created` and `session-update`. */
@Entity(tableName = "session_row")
class SessionRowEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    /** The AgentSession JSON, sealed. */
    @ColumnInfo(name = "blob") val blob: ByteArray,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long?,
    @ColumnInfo(name = "pinned") val pinned: Boolean,
    @ColumnInfo(name = "runtime_archived") val runtimeArchived: Boolean,
    /** v130 `AgentSession.lastSeq` (S13.1), when the server sends it; a hint only. */
    @ColumnInfo(name = "server_last_seq") val serverLastSeq: Long?,
    /** Missing from the last full `ready` list (retention-pruned); never a silent delete (§5.4). */
    @ColumnInfo(name = "gone_from_server") val goneFromServer: Boolean,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long?,
)

/** Exactly one base per session: the last server `state`, or a local checkpoint (§2.4). */
@Entity(tableName = "session_base")
class SessionBaseEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "through_seq") val throughSeq: Long,
    @ColumnInfo(name = "trimmed_before") val trimmedBefore: Int?,
    /** [ORIGIN_SERVER] or [ORIGIN_LOCAL]. */
    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "reducer_version") val reducerVersion: String,
    /** The projection JSON, gzip then sealed. */
    @ColumnInfo(name = "state") val state: ByteArray,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
) {
    companion object {
        const val ORIGIN_SERVER = "server"
        const val ORIGIN_LOCAL = "local"
    }
}

/** A live event with `base.through_seq < seq <= cursor`, verbatim. */
@Entity(tableName = "journal_event", primaryKeys = ["session_id", "seq"])
class JournalEventEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "seq") val seq: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "ts") val ts: Long?,
    /** The event JSON, sealed. */
    @ColumnInfo(name = "payload") val payload: ByteArray,
)

/** A server `turns-detail` turn, spliced into the base on rebuild. Re-fetchable, so evicted first. */
@Entity(tableName = "turn_detail", primaryKeys = ["session_id", "turn_id"])
class TurnDetailEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "turn_id") val turnId: String,
    @ColumnInfo(name = "turn_index") val turnIndex: Int?,
    /** The turn projection JSON, sealed. */
    @ColumnInfo(name = "payload") val payload: ByteArray,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/**
 * Per-session sync bookkeeping. [cursor] is written in the SAME transaction as the rows it
 * covers, so it never claims more than the DB holds (§2.3). Null = no usable cursor: the
 * next attach is a full one.
 */
@Entity(tableName = "sync_state")
class SyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "cursor") val cursor: Long?,
    /** The server confirmed head (a snapshot) on a live connection. */
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long?,
    /** `list` (row only) or `full` (base + tail). */
    @ColumnInfo(name = "level") val level: String,
    @ColumnInfo(name = "bytes") val bytes: Long,
)

/** Schema/app version, origin, reducer version, data-key id, the per-key write counter. */
@Entity(tableName = "meta")
class MetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)
