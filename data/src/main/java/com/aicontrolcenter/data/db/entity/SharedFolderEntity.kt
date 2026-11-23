package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per folder the owner picked (`SharedFolder`). [treeUri] is unique: one tree is one
 * folder, so the grants narrowed to a folder id can never silently move to another folder.
 * Rows are updated in place by id, never replaced.
 */
@Entity(
    tableName = SharedFolderEntity.TABLE,
    indices = [Index(value = ["tree_uri"], unique = true)],
)
internal data class SharedFolderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "read_only") val readOnly: Boolean,
) {
    companion object {
        const val TABLE = "shared_folders"
    }
}
