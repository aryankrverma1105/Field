package com.sologix.attendance.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Phase 1 Item 5: A Single CoroutineWorker handling offline sync.
 *
 * Reads PENDING/FAILED items from sync_queue, invokes matching network calls,
 * and updates Room entities. Failures increment attempt_count and trigger
 * WorkManager's exponential backoff, dead-lettering to FAILED after max attempts.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME_ONE_TIME = "sologix_one_time_sync"
        const val WORK_NAME_PERIODIC = "sologix_periodic_sync"
        const val MAX_ATTEMPT_COUNT = 5
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(applicationContext)
        val syncDao = db.syncQueueDao()

        // 1. Fetch pending or failed items in sequence
        val queueItems = syncDao.getPendingOrFailed(limit = 20)
        if (queueItems.isEmpty()) {
            return@withContext Result.success()
        }

        var anyFailed = false

        for (item in queueItems) {
            try {
                // Mark IN_PROGRESS
                syncDao.markInProgress(item.operationId)

                // Dispatch to network
                val success = dispatchMutation(item.operationType, item.payloadJson, item.operationId)

                if (success) {
                    // Update sync_queue to SYNCED
                    syncDao.markSynced(item.operationId)

                    // Update parent entity syncState to SYNCED in Room
                    updateEntitySyncState(db, item.entityType, item.entityId, SyncState.SYNCED)
                } else {
                    anyFailed = true
                    handleFailure(syncDao, db, item.operationId, item.entityType, item.entityId, item.attemptCount)
                }
            } catch (e: Exception) {
                anyFailed = true
                handleFailure(syncDao, db, item.operationId, item.entityType, item.entityId, item.attemptCount)
            }
        }

        if (anyFailed) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private suspend fun handleFailure(
        syncDao: com.sologix.attendance.data.local.dao.SyncQueueDao,
        db: AppDatabase,
        operationId: String,
        entityType: String,
        entityId: String,
        currentAttempts: Int
    ) {
        syncDao.recordFailure(operationId, System.currentTimeMillis())
        if (currentAttempts + 1 >= MAX_ATTEMPT_COUNT) {
            // Dead-letter to manual retry UI
            updateEntitySyncState(db, entityType, entityId, SyncState.FAILED)
        }
    }

    private suspend fun updateEntitySyncState(
        db: AppDatabase,
        entityType: String,
        entityId: String,
        state: SyncState
    ) {
        when (entityType) {
            "ATTENDANCE" -> db.attendanceDao().updateSyncState(entityId, state)
            "TASK" -> db.taskDao().updateSyncState(entityId, state)
            "CUSTOMER" -> db.customerDao().updateSyncState(entityId, state)
            "VISIT" -> db.visitDao().updateSyncState(entityId, state)
            "GPS_POINT" -> db.gpsPointDao().updateSyncState(entityId, state)
        }
    }

    /**
     * Dispatches mutation payload to the backend REST API.
     */
    private suspend fun dispatchMutation(
        operationType: OperationType,
        payloadJson: String,
        operationId: String
    ): Boolean {
        // Mock / Retrofit dispatcher
        // In full integration this invokes ApiService.postSync(operationId, body)
        return true
    }
}
