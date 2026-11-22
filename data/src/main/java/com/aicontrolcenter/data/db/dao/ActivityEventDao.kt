package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import com.aicontrolcenter.data.db.entity.ActivityEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ActivityEventDao {
    /**
     * Appends an event. The log is append-only, so an event whose id is already stored is
     * ignored rather than rewritten: recording the same event twice is harmless and history is
     * never altered. Returns the new row's sequence number, or -1 when the id was already there.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ActivityEventEntity): Long

    /** Runs a query built by `ActivityQueryBuilder`; re-emits whenever the table changes. */
    @RawQuery(observedEntities = [ActivityEventEntity::class])
    fun observe(query: RoomRawQuery): Flow<List<ActivityEventEntity>>

    @RawQuery
    suspend fun select(query: RoomRawQuery): List<ActivityEventEntity>

    @Query("DELETE FROM activity_events WHERE at < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int

    @Query("DELETE FROM activity_events")
    suspend fun deleteAll()
}
