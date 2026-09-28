package com.sologix.attendance.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.remote.ApiService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val db: AppDatabase,
    private val apiService: ApiService
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME_ONE_TIME = "sologix_one_time_sync"
        const val WORK_NAME_PERIODIC = "sologix_periodic_sync"
        const val MAX_ATTEMPT_COUNT = 5
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncDao = db.syncQueueDao()

        val queueItems = syncDao.getPendingOrFailed(limit = 50)
        if (queueItems.isEmpty()) {
            return@withContext Result.success()
        }

        var anyFailed = false
        val failedEntityIds = mutableSetOf<String>()

        for (item in queueItems) {
            // Per-entity ordering: check if this entity already failed in this run
            if (failedEntityIds.contains(item.entityId)) {
                continue
            }

            // Per-entity ordering: check if earlier unsynced operation exists in DB for this entity
            val hasEarlier = syncDao.hasEarlierUnsyncedOperations(item.entityId, item.createdAt)
            if (hasEarlier) {
                // Check-out or later operation must wait for its earlier operation (e.g. check-in)
                continue
            }

            // Atomic claim: only proceed if row was successfully updated from PENDING/FAILED to IN_PROGRESS
            val claimed = syncDao.markInProgressAtomic(item.operationId)
            if (claimed != 1) {
                // Row claimed by another worker or already completed
                continue
            }

            // Dispatch mutation to network
            val result = dispatchMutation(item.operationType, item.payloadJson)

            when (result) {
                is DispatchResult.Success -> {
                    syncDao.markSynced(item.operationId)
                    updateEntitySyncState(db, item.entityType, item.entityId, SyncState.SYNCED)
                }

                is DispatchResult.Transient -> {
                    anyFailed = true
                    failedEntityIds.add(item.entityId)
                    val nextAttempt = item.attemptCount + 1
                    if (nextAttempt >= MAX_ATTEMPT_COUNT) {
                        syncDao.markDead(item.operationId, "Max retry limit ($MAX_ATTEMPT_COUNT) reached: ${result.reason}")
                        updateEntitySyncState(db, item.entityType, item.entityId, SyncState.FAILED)
                    } else {
                        syncDao.recordFailure(item.operationId, result.reason)
                        updateEntitySyncState(db, item.entityType, item.entityId, SyncState.FAILED)
                    }
                }

                is DispatchResult.Permanent -> {
                    anyFailed = true
                    failedEntityIds.add(item.entityId)
                    syncDao.markDead(item.operationId, result.reason)
                    updateEntitySyncState(db, item.entityType, item.entityId, SyncState.FAILED)
                }

                is DispatchResult.AuthRequired -> {
                    // 401 -> AuthRequired (do not increment attempt_count; stop the run)
                    syncDao.resetInProgressWithoutIncrement(item.operationId)
                    return@withContext Result.retry()
                }

                is DispatchResult.Unsupported -> {
                    // Any OperationType without a real endpoint yet -> Unsupported.
                    // It must never be marked SYNCED. It stays visible and un-synced until its endpoint exists.
                    // Do not increment attempt_count, reset status back to PENDING.
                    syncDao.resetInProgressToPending(item.operationId)
                    failedEntityIds.add(item.entityId)
                }
            }
        }

        if (anyFailed) {
            Result.retry()
        } else {
            Result.success()
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

    suspend fun dispatchMutation(
        operationType: OperationType,
        payloadJson: String
    ): DispatchResult {
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payloadJson.toRequestBody(jsonMediaType)

        return try {
            val response = when (operationType) {
                OperationType.CHECK_IN -> apiService.checkIn(requestBody)
                OperationType.CHECK_OUT -> apiService.checkOut(requestBody)
                OperationType.GPS_POINT -> apiService.sendGpsPoint(requestBody)
                else -> return DispatchResult.Unsupported("OperationType $operationType has no endpoint yet")
            }

            classifyResponse(response.code(), response.errorBody()?.string())
        } catch (e: IOException) {
            DispatchResult.Transient("Network I/O error: ${e.message}")
        } catch (e: Exception) {
            DispatchResult.Permanent("Unexpected error: ${e.message}")
        }
    }

    private fun classifyResponse(code: Int, errorBody: String?): DispatchResult {
        return when {
            code in 200..299 -> DispatchResult.Success
            code == 401 -> DispatchResult.AuthRequired
            code == 408 || code == 429 || code in 500..599 -> DispatchResult.Transient("HTTP $code: ${errorBody ?: ""}")
            code in 400..499 -> DispatchResult.Permanent("HTTP $code: ${errorBody ?: ""}")
            else -> DispatchResult.Permanent("Unexpected HTTP status $code")
        }
    }
}
