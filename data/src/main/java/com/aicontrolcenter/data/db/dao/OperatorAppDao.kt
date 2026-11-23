package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.aicontrolcenter.data.db.entity.OperatorAppEntity
import kotlinx.coroutines.flow.Flow

/** Apps authorised for screen operation, keyed by package name; inserted or updated in place. */
@Dao
internal interface OperatorAppDao {
    @Query("SELECT * FROM operator_apps ORDER BY authorised_at ASC, package_name ASC")
    fun observeAll(): Flow<List<OperatorAppEntity>>

    @Query("SELECT * FROM operator_apps ORDER BY authorised_at ASC, package_name ASC")
    suspend fun getAll(): List<OperatorAppEntity>

    @Query("SELECT * FROM operator_apps WHERE package_name = :packageName")
    suspend fun get(packageName: String): OperatorAppEntity?

    @Upsert
    suspend fun upsert(app: OperatorAppEntity)

    @Query("DELETE FROM operator_apps WHERE package_name = :packageName")
    suspend fun delete(packageName: String)
}
