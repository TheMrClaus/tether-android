package com.tether.app.mirror

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/**
 * Blocking DAO: every call runs on [JournalMirror]'s single writer thread, inside its
 * batch transaction, so there is exactly one writer per DB and per-session order holds.
 */
@Dao
interface MirrorDao {
    // --- session_row ---
    @Upsert
    fun upsertSessionRows(rows: List<SessionRowEntity>)

    @Query("SELECT * FROM session_row")
    fun sessionRows(): List<SessionRowEntity>

    @Query("SELECT * FROM session_row WHERE session_id IN (:ids)")
    fun sessionRowsById(ids: List<String>): List<SessionRowEntity>

    @Query("UPDATE session_row SET gone_from_server = 1")
    fun markAllGone()

    @Query("UPDATE session_row SET last_opened_at = :at WHERE session_id = :sessionId")
    fun markOpened(sessionId: String, at: Long)

    @Query("DELETE FROM session_row WHERE session_id = :sessionId")
    fun deleteSessionRow(sessionId: String)

    // --- session_base ---
    @Query("SELECT * FROM session_base WHERE session_id = :sessionId")
    fun base(sessionId: String): SessionBaseEntity?

    @Upsert
    fun upsertBase(base: SessionBaseEntity)

    @Query("DELETE FROM session_base WHERE session_id = :sessionId")
    fun deleteBase(sessionId: String)

    @Query("SELECT session_id FROM session_base WHERE origin = :origin")
    fun baseIdsWithOrigin(origin: String): List<String>

    // --- journal_event ---
    /** A conflict is a duplicate delivery: ignored (-1). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEvent(event: JournalEventEntity): Long

    @Query("SELECT * FROM journal_event WHERE session_id = :sessionId ORDER BY seq")
    fun events(sessionId: String): List<JournalEventEntity>

    @Query("SELECT COUNT(*) FROM journal_event WHERE session_id = :sessionId")
    fun eventCount(sessionId: String): Int

    @Query("DELETE FROM journal_event WHERE session_id = :sessionId")
    fun deleteEvents(sessionId: String)

    @Query("DELETE FROM journal_event WHERE session_id = :sessionId AND seq <= :throughSeq")
    fun deleteEventsThrough(sessionId: String, throughSeq: Long)

    // --- turn_detail ---
    @Upsert
    fun upsertTurnDetails(rows: List<TurnDetailEntity>)

    @Query("SELECT * FROM turn_detail WHERE session_id = :sessionId")
    fun turnDetails(sessionId: String): List<TurnDetailEntity>

    @Query("SELECT turn_id FROM turn_detail WHERE session_id = :sessionId")
    fun turnDetailIds(sessionId: String): List<String>

    @Query("DELETE FROM turn_detail WHERE session_id = :sessionId AND turn_id = :turnId")
    fun deleteTurnDetail(sessionId: String, turnId: String)

    @Query("DELETE FROM turn_detail WHERE session_id = :sessionId")
    fun deleteTurnDetails(sessionId: String)

    // --- sync_state ---
    @Query("SELECT * FROM sync_state WHERE session_id = :sessionId")
    fun syncState(sessionId: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state")
    fun syncStates(): List<SyncStateEntity>

    @Upsert
    fun upsertSyncState(state: SyncStateEntity)

    @Query("UPDATE sync_state SET cursor = NULL WHERE session_id = :sessionId")
    fun clearCursor(sessionId: String)

    @Query("UPDATE sync_state SET cursor = :cursor WHERE session_id = :sessionId")
    fun setCursor(sessionId: String, cursor: Long)

    @Query("UPDATE sync_state SET last_verified_at = :at WHERE session_id = :sessionId")
    fun setVerified(sessionId: String, at: Long)

    @Query("DELETE FROM sync_state WHERE session_id = :sessionId")
    fun deleteSyncState(sessionId: String)

    // --- meta ---
    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun meta(key: String): String?

    @Upsert
    fun putMeta(meta: MetaEntity)
}
