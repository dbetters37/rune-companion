package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.aicontrolcenter.data.db.entity.AiAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AiAppDao {
    @Query("SELECT * FROM ai_apps ORDER BY first_seen_at ASC, id ASC")
    fun observeAll(): Flow<List<AiAppEntity>>

    @Query("SELECT * FROM ai_apps ORDER BY first_seen_at ASC, id ASC")
    suspend fun getAll(): List<AiAppEntity>

    @Query("SELECT * FROM ai_apps WHERE id = :id")
    suspend fun get(id: String): AiAppEntity?

    /** Normally one row; the registry service keeps packages unique. Oldest first if there are more. */
    @Query("SELECT * FROM ai_apps WHERE package_name = :packageName ORDER BY first_seen_at ASC, id ASC")
    suspend fun getByPackage(packageName: String): List<AiAppEntity>

    @Upsert
    suspend fun upsert(app: AiAppEntity)

    @Query("DELETE FROM ai_apps WHERE id = :id")
    suspend fun delete(id: String)
}
