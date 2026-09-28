package com.sologix.attendance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SyncQueueEntity)

    @Query("SELECT * FROM sync_queue WHERE status IN ('PENDING', 'FAILED') ORDER BY created_at ASC LIMIT :limit")
    suspend fun getPendingOrFailed(limit: Int = 50): List<SyncQueueEntity>

    @Query("UPDATE sync_queue SET status = 'IN_PROGRESS' WHERE operation_id = :operationId")
    suspend fun markInProgress(operationId: String)

    @Query("UPDATE sync_queue SET status = 'SYNCED' WHERE operation_id = :operationId")
    suspend fun markSynced(operationId: String)

    @Query("""
        UPDATE sync_queue 
        SET status = 'FAILED', 
            attempt_count = attempt_count + 1, 
            last_attempt_at = :now 
        WHERE operation_id = :operationId
    """)
    suspend fun recordFailure(operationId: String, now: Long = System.currentTimeMillis())

    /**
     * Phase 1 Item 7: Crash-safety on launch.
     * Resets any sync_queue row stuck in IN_PROGRESS back to FAILED so an interrupted
     * sync is picked up by the next worker run rather than orphaned.
     */
    @Query("UPDATE sync_queue SET status = 'FAILED' WHERE status = 'IN_PROGRESS'")
    suspend fun resetInProgressToFailed(): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status IN ('PENDING', 'FAILED', 'IN_PROGRESS')")
    fun observePendingCount(): Flow<Int>

    /**
     * Phase 1 Item 10: StateFlow propagation for UI manual retry screen.
     */
    @Query("SELECT * FROM sync_queue ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SyncQueueEntity>>

    @Query("SELECT operation_id FROM sync_queue WHERE status IN ('PENDING', 'IN_PROGRESS', 'FAILED')")
    suspend fun getPendingOperationIds(): List<String>

    @Query("SELECT entity_id FROM sync_queue WHERE status IN ('PENDING', 'IN_PROGRESS', 'FAILED')")
    suspend fun getPendingEntityIds(): List<String>
}
