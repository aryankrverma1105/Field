package com.sologix.attendance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AttendanceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AttendanceEntity>)

    @Update
    suspend fun update(entity: AttendanceEntity)

    @Query("SELECT * FROM attendance ORDER BY check_in_at DESC LIMIT 1")
    fun observeLatest(): Flow<AttendanceEntity?>

    @Query("SELECT * FROM attendance ORDER BY check_in_at DESC LIMIT 1")
    suspend fun getLatest(): AttendanceEntity?

    @Query("SELECT * FROM attendance WHERE id = :id")
    suspend fun getById(id: String): AttendanceEntity?

    @Query("UPDATE attendance SET sync_state = :syncState WHERE id = :id")
    suspend fun updateSyncState(id: String, syncState: SyncState)

    @Query("SELECT * FROM attendance ORDER BY check_in_at DESC")
    fun observeAll(): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance")
    suspend fun getAll(): List<AttendanceEntity>
}
