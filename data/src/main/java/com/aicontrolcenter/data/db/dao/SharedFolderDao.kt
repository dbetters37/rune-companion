package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.aicontrolcenter.data.db.entity.SharedFolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Shared folders. A folder is updated in place by id and never replaced: `REPLACE` would delete
 * another folder that holds the same tree and leave its grants pointing at nothing.
 */
@Dao
internal interface SharedFolderDao {
    @Query("SELECT * FROM shared_folders ORDER BY added_at ASC, id ASC")
    fun observeAll(): Flow<List<SharedFolderEntity>>

    @Query("SELECT * FROM shared_folders ORDER BY added_at ASC, id ASC")
    suspend fun getAll(): List<SharedFolderEntity>

    @Query("SELECT * FROM shared_folders WHERE id = :id")
    suspend fun get(id: String): SharedFolderEntity?

    @Query("SELECT * FROM shared_folders WHERE tree_uri = :treeUri")
    suspend fun getByTreeUri(treeUri: String): SharedFolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: SharedFolderEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(folder: SharedFolderEntity): Int

    /**
     * Updates the folder with this id, or adds it. Returns false, writing nothing, when another
     * folder already holds the same tree. The check and the write share one transaction.
     */
    @Transaction
    suspend fun upsert(folder: SharedFolderEntity): Boolean {
        val holder = getByTreeUri(folder.treeUri)
        if (holder != null && holder.id != folder.id) return false
        if (update(folder) == 0) insert(folder)
        return true
    }

    @Query("DELETE FROM shared_folders WHERE id = :id")
    suspend fun delete(id: String)
}
