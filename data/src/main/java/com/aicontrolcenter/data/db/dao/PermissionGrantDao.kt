package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aicontrolcenter.data.db.entity.PermissionGrantEntity
import kotlinx.coroutines.flow.Flow

/**
 * Permission grants. Insert-only: a row is never replaced or deleted. Every change of state is
 * one conditional `UPDATE` whose `WHERE` matches only a grant still in force (neither revoked
 * nor consumed), and its row count says whether it happened, so concurrent callers can never
 * both end the same grant. "In force" here ignores expiry, like the listings; only consuming
 * a ONCE grant also requires it not to have expired.
 */
@Dao
internal interface PermissionGrantDao {
    @Query("SELECT * FROM permission_grants WHERE agent_id = :agentId AND $IN_FORCE ORDER BY granted_at ASC, id ASC")
    fun observeInForce(agentId: String): Flow<List<PermissionGrantEntity>>

    @Query("SELECT * FROM permission_grants WHERE $IN_FORCE ORDER BY granted_at ASC, id ASC")
    fun observeAllInForce(): Flow<List<PermissionGrantEntity>>

    @Query("SELECT * FROM permission_grants WHERE id = :id")
    suspend fun get(id: String): PermissionGrantEntity?

    /** Returns the new row id, or -1 when a grant with this id exists already (nothing is written then). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(grant: PermissionGrantEntity): Long

    /** The grants in force for one agent, capability and constraint; the caller applies scope and expiry. */
    @Query(
        "SELECT * FROM permission_grants WHERE agent_id = :agentId AND capability = :capability " +
            "AND constraint_key = :constraintKey AND $IN_FORCE",
    )
    suspend fun candidates(agentId: String, capability: String, constraintKey: String): List<PermissionGrantEntity>

    @Query("SELECT * FROM permission_grants WHERE request_id = :requestId ORDER BY granted_at ASC, id ASC LIMIT 1")
    suspend fun firstForRequest(requestId: String): PermissionGrantEntity?

    @Query("SELECT * FROM permission_grants WHERE id = ($ONCE_OF_REQUEST)")
    suspend fun onceForRequest(requestId: String): PermissionGrantEntity?

    @Query("UPDATE permission_grants SET revoked_at = :at WHERE id = :id AND $IN_FORCE")
    suspend fun revoke(id: String, at: Long): Int

    @Query("UPDATE permission_grants SET revoked_at = :at WHERE agent_id = :agentId AND $IN_FORCE")
    suspend fun revokeAllFor(agentId: String, at: Long): Int

    @Query("UPDATE permission_grants SET revoked_at = :at WHERE constraint_key = :constraintKey AND $IN_FORCE")
    suspend fun revokeAllWithConstraint(constraintKey: String, at: Long): Int

    @Query("UPDATE permission_grants SET revoked_at = :at WHERE task_id = :taskId AND $TASK_BOUND AND $IN_FORCE")
    suspend fun revokeAllForTask(taskId: String, at: Long): Int

    @Query(
        "UPDATE permission_grants SET consumed_at = :at WHERE id = ($ONCE_OF_REQUEST) AND $IN_FORCE " +
            "AND (expires_at IS NULL OR expires_at > :at)",
    )
    suspend fun consumeOnce(requestId: String, at: Long): Int

    @Query("UPDATE permission_grants SET revoked_at = :at WHERE id = ($ONCE_OF_REQUEST) AND $IN_FORCE")
    suspend fun releaseOnce(requestId: String, at: Long): Int

    /** [revoke], then the row as written; null when nothing changed. The row cannot change again once revoked. */
    @Transaction
    suspend fun revokeAndGet(id: String, at: Long): PermissionGrantEntity? =
        if (revoke(id, at) == 1) get(id) else null

    /** [consumeOnce], then the consumed row; null when nothing changed. */
    @Transaction
    suspend fun consumeOnceAndGet(requestId: String, at: Long): PermissionGrantEntity? =
        if (consumeOnce(requestId, at) == 1) onceForRequest(requestId) else null

    /** [releaseOnce], then the revoked row; null when nothing changed. */
    @Transaction
    suspend fun releaseOnceAndGet(requestId: String, at: Long): PermissionGrantEntity? =
        if (releaseOnce(requestId, at) == 1) onceForRequest(requestId) else null

    /**
     * The conditions and sub-queries the statements share, as constants so `StoredNamesGoldenTest`
     * can check the stored `GrantScope` names they spell out.
     */
    companion object {
        /** Neither revoked nor consumed. */
        const val IN_FORCE = "revoked_at IS NULL AND consumed_at IS NULL"

        /** The scopes that end with their task: TASK and ONCE (ALWAYS grants have no task). */
        const val TASK_BOUND = "scope IN ('TASK', 'ONCE')"

        /**
         * The ONCE grant created by answering a request. A request is answered once, so there is
         * at most one; should a grant with the same request id ever be added by hand, the oldest
         * is the request's own.
         */
        const val ONCE_OF_REQUEST =
            "SELECT id FROM permission_grants WHERE request_id = :requestId AND scope = 'ONCE' ORDER BY granted_at ASC, id ASC LIMIT 1"
    }
}
