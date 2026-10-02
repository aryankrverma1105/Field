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

    @Query("SELECT * FROM sync_queue WHERE operation_id = :operationId")
    suspend fun getByOperationId(operationId: String): SyncQueueEntity?

    @Query("SELECT * FROM sync_queue WHERE status IN ('PENDING', 'FAILED') ORDER BY created_at ASC LIMIT :limit")
    suspend fun getPendingOrFailed(limit: Int = 50): List<SyncQueueEntity>

    @Query("UPDATE sync_queue SET status = 'IN_PROGRESS' WHERE operation_id = :operationId AND status IN ('PENDING', 'FAILED')")
    suspend fun markInProgressAtomic(operationId: String): Int

    @Query("UPDATE sync_queue SET status = 'SYNCED' WHERE operation_id = :operationId")
    suspend fun markSynced(operationId: String)

    @Query("""
        UPDATE sync_queue 
        SET status = 'FAILED', 
            attempt_count = attempt_count + 1, 
            last_attempt_at = :now,
            last_error = :error
        WHERE operation_id = :operationId
    """)
    suspend fun recordFailure(operationId: String, error: String?, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE sync_queue 
        SET status = 'DEAD', 
            attempt_count = attempt_count + 1, 
            last_attempt_at = :now,
            last_error = :error
        WHERE operation_id = :operationId
    """)
    suspend fun markDead(operationId: String, error: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE sync_queue SET status = 'PENDING', attempt_count = 0, last_error = NULL WHERE operation_id = :operationId")
    suspend fun retryDead(operationId: String)

    @Query("UPDATE sync_queue SET status = 'PENDING' WHERE operation_id = :operationId AND status = 'IN_PROGRESS'")
    suspend fun resetInProgressToPending(operationId: String)

    @Query("UPDATE sync_queue SET status = 'FAILED' WHERE operation_id = :operationId AND status = 'IN_PROGRESS'")
    suspend fun resetInProgressWithoutIncrement(operationId: String)

    /**
     * Phase 1 Item 7: Crash-safety on launch.
     * Resets any sync_queue row stuck in IN_PROGRESS back to FAILED so an interrupted
     * sync is picked up by the next worker run rather than orphaned.
     */
    @Query("UPDATE sync_queue SET status = 'FAILED' WHERE status = 'IN_PROGRESS'")
    suspend fun resetInProgressToFailed(): Int

    @Query("SELECT COUNT(*) > 0 FROM sync_queue WHERE entity_id = :entityId AND created_at < :createdAt AND status != 'SYNCED'")
    suspend fun hasEarlierUnsyncedOperations(entityId: String, createdAt: Long): Boolean

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status IN ('PENDING', 'FAILED', 'IN_PROGRESS')")
    fun observePendingCount(): Flow<Int>

    /**
     * Phase 1 Item 10: StateFlow propagation for UI manual retry screen.
     */
    @Query("SELECT * FROM sync_queue ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SyncQueueEntity>>

    @Query("SELECT operation_id FROM sync_queue WHERE status IN ('PENDING', 'IN_PROGRESS', 'FAILED')")
    suspend fun getPendingOperationIds(): List<String>

    @Query("SELECT DISTINCT entity_id FROM sync_queue WHERE status IN ('PENDING', 'IN_PROGRESS', 'FAILED', 'DEAD')")
    suspend fun getPendingEntityIds(): List<String>
}
