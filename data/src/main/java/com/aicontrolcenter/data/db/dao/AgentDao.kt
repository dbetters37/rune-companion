package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.aicontrolcenter.data.db.entity.AgentEntity
import com.aicontrolcenter.data.db.entity.AgentStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY created_at ASC, id ASC")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id")
    fun observe(id: String): Flow<AgentEntity?>

    @Query("SELECT * FROM agents ORDER BY created_at ASC, id ASC")
    suspend fun getAll(): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun get(id: String): AgentEntity?

    @Upsert
    suspend fun upsert(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("DELETE FROM agent_stats WHERE agent_id = :id")
    suspend fun deleteStats(id: String)

    /** Removes the profile and its track record together, so neither can outlive the other. */
    @Transaction
    suspend fun deleteWithStats(id: String) {
        deleteProfile(id)
        deleteStats(id)
    }

    @Query("SELECT * FROM agent_stats")
    fun observeStats(): Flow<List<AgentStatsEntity>>

    @Query("SELECT * FROM agent_stats WHERE agent_id = :id")
    suspend fun getStats(id: String): AgentStatsEntity?

    @Upsert
    suspend fun upsertStats(stats: AgentStatsEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM agents WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /**
     * Read-modify-write of one stats row inside a single write transaction, so concurrent
     * updates queue up instead of overwriting each other. [transform] receives null when the
     * agent has no stats yet; it runs while the database is locked for writing and must be
     * quick and free of side effects.
     *
     * Nothing is written, and [transform] is not called, when no agent has this id. The check
     * is part of the same transaction as the write, and so is the removal in [deleteWithStats],
     * so a stats row can never be left behind for a deleted agent.
     *
     * @return whether the agent exists and its stats were written.
     */
    @Transaction
    suspend fun updateStats(id: String, transform: (AgentStatsEntity?) -> AgentStatsEntity): Boolean {
        if (!exists(id)) return false
        upsertStats(transform(getStats(id)))
        return true
    }
}
