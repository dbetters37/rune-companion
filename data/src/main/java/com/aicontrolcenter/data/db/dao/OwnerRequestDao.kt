package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicontrolcenter.data.db.entity.OwnerRequestEntity
import com.aicontrolcenter.data.db.entity.PermissionGrantEntity
import kotlinx.coroutines.flow.Flow

/**
 * Owner requests. A row is inserted once and leaves `PENDING` only through [closePending]: one
 * conditional `UPDATE … WHERE id = ? AND status = 'PENDING'` whose row count says whether this
 * caller closed it. Rows are never deleted.
 *
 * [insertGrant] is here so that answering a request and storing its grant can share one
 * transaction (`RoomOwnerRequestRepository.decide`).
 */
@Dao
internal interface OwnerRequestDao {
    /** Returns the new row id, or -1 when a request with this id exists already (nothing is written then). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(request: OwnerRequestEntity): Long

    @Query("SELECT * FROM owner_requests WHERE id = :id")
    suspend fun get(id: String): OwnerRequestEntity?

    @Query("SELECT * FROM owner_requests WHERE id = :id")
    fun observe(id: String): Flow<OwnerRequestEntity?>

    @Query(PENDING)
    fun observePending(): Flow<List<OwnerRequestEntity>>

    @Query(PENDING)
    suspend fun pending(): List<OwnerRequestEntity>

    @Query("SELECT * FROM owner_requests WHERE $IS_PENDING AND expires_at <= :at ORDER BY created_at ASC, id ASC")
    suspend fun pendingDueBy(at: Long): List<OwnerRequestEntity>

    /** Pending asks of one task: requests an agent raised itself (`permissions.request`), which have no tool. */
    @Query("SELECT * FROM owner_requests WHERE $IS_PENDING AND tool_id IS NULL AND task_id = :taskId ORDER BY created_at ASC, id ASC")
    suspend fun pendingAsksOf(taskId: String): List<OwnerRequestEntity>

    @Query("SELECT * FROM owner_requests ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OwnerRequestEntity>>

    @Query("SELECT * FROM owner_requests WHERE conversation_id = :conversationId ORDER BY created_at ASC, id ASC")
    fun observeForConversation(conversationId: String): Flow<List<OwnerRequestEntity>>

    @Query("SELECT * FROM owner_requests WHERE root_id = :rootId ORDER BY created_at ASC, id ASC")
    fun observeForObjective(rootId: String): Flow<List<OwnerRequestEntity>>

    /**
     * The only way out of `PENDING`: sets the outcome if, and only if, the request is still
     * pending. Returns 1 when this call closed it, 0 when it was missing or already closed.
     * [answer] is the owner's answer to a QUESTION (schema 6), null for everything else.
     */
    @Query(
        "UPDATE owner_requests SET status = :status, decided_at = :at, decision_scope = :scope, decision_note = :note, " +
            "answer = :answer WHERE id = :id AND $IS_PENDING",
    )
    suspend fun closePending(id: String, status: String, at: Long, scope: String?, note: String?, answer: String?): Int

    /**
     * The newest PERMISSION request of the agent for the capability and target that the owner
     * refused at or after [since] and has not opened for asking again. `PERMISSION` and `DENIED`
     * are stored names pinned by `StoredNamesGoldenTest`.
     */
    @Query(
        "SELECT * FROM owner_requests WHERE agent_id = :agentId AND capability = :capability AND constraint_key = :constraintKey " +
            "AND $IS_REFUSAL AND ask_again_at IS NULL AND decided_at >= :since ORDER BY decided_at DESC, id DESC LIMIT 1",
    )
    suspend fun lastRefusal(agentId: String, capability: String, constraintKey: String, since: Long): OwnerRequestEntity?

    /**
     * The owner lets the agent ask again: marks every refusal of the agent for the capability
     * and target that is not marked yet. Returns how many rows were marked.
     */
    @Query(
        "UPDATE owner_requests SET ask_again_at = :at WHERE agent_id = :agentId AND capability = :capability " +
            "AND constraint_key = :constraintKey AND $IS_REFUSAL AND ask_again_at IS NULL",
    )
    suspend fun letAskAgain(agentId: String, capability: String, constraintKey: String, at: Long): Int

    /** One more ask refused during a refusal's cool-down. Returns 1 when the refused request was counted, 0 otherwise. */
    @Query("UPDATE owner_requests SET asked_again = asked_again + 1 WHERE id = :id AND status = 'DENIED'")
    suspend fun countAskedAgain(id: String): Int

    /** Returns the new row id, or -1 when a grant with this id exists already. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGrant(grant: PermissionGrantEntity): Long

    companion object {
        /** `PENDING` is the stored name of `OwnerRequestStatus.PENDING`, pinned by `StoredNamesGoldenTest`. */
        const val IS_PENDING = "status = 'PENDING'"

        /** A permission the owner refused; the stored names are pinned by `StoredNamesGoldenTest`. */
        const val IS_REFUSAL = "kind = 'PERMISSION' AND status = 'DENIED'"

        const val PENDING = "SELECT * FROM owner_requests WHERE $IS_PENDING ORDER BY created_at ASC, id ASC"
    }
}
