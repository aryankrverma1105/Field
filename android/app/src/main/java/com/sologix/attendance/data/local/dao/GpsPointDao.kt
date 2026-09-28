package com.sologix.attendance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sologix.attendance.data.local.entity.GpsPointEntity
import com.sologix.attendance.data.local.entity.SyncState

@Dao
interface GpsPointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GpsPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<GpsPointEntity>)

    @Query("UPDATE gps_points SET sync_state = :syncState WHERE id = :id")
    suspend fun updateSyncState(id: String, syncState: SyncState)

    @Query("SELECT * FROM gps_points ORDER BY recorded_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<GpsPointEntity>
}
