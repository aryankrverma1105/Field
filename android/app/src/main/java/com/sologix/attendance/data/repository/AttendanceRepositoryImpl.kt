package com.sologix.attendance.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.sologix.attendance.data.hydration.HydrationMergeGuard
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.remote.ApiService
import com.sologix.attendance.service.LocationTrackingService
import com.sologix.attendance.sync.SyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttendanceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val apiService: ApiService
) : AttendanceRepository {

    private val attendanceDao = db.attendanceDao()
    private val syncQueueDao = db.syncQueueDao()

    override fun observeLatest(): Flow<AttendanceEntity?> = attendanceDao.observeLatest()

    override suspend fun getLatest(): AttendanceEntity? = attendanceDao.getLatest()

    override suspend fun getById(id: String): AttendanceEntity? = attendanceDao.getById(id)

    private fun formatIsoUtc(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMs))
    }

    override suspend fun checkIn(
        userId: String,
        lat: Double,
        lng: Double,
        photoPath: String?,
        isMocked: Boolean,
        attendanceId: String?,
        operationId: String?
    ): AttendanceWriteResult {
        val existing = attendanceId?.let { attendanceDao.getById(it) }

        // Reuse IDs and operationId on retry if already existing
        val targetAttendanceId = existing?.id ?: attendanceId ?: UUID.randomUUID().toString()
        val targetOpId = existing?.checkInOperationId ?: operationId ?: UUID.randomUUID().toString()
        val now = existing?.checkInAt ?: System.currentTimeMillis()

        // Part A.2: Check if this operation has already reached DEAD status
        val existingQueueItem = syncQueueDao.getByOperationId(targetOpId)
        if (existingQueueItem != null && existingQueueItem.status == QueueStatus.DEAD) {
            return AttendanceWriteResult.BlockedByDeadOperation(
                reason = existingQueueItem.lastError,
                operationId = targetOpId
            )
        }

        val entity = existing?.copy(syncState = SyncState.PENDING) ?: AttendanceEntity(
            id = targetAttendanceId,
            userId = userId,
            checkInAt = now,
            checkInLat = lat,
            checkInLng = lng,
            checkInPhotoPath = photoPath,
            checkInIsMocked = isMocked,
            checkInOperationId = targetOpId,
            geofenceStatus = "UNKNOWN",
            syncState = SyncState.PENDING,
            createdAt = now
        )

        val payload = JSONObject().apply {
            put("id", targetAttendanceId)
            put("userId", userId)
            put("checkInAt", formatIsoUtc(now))
            put("checkInLat", lat)
            put("checkInLng", lng)
            put("checkInPhotoPath", photoPath)
            put("checkInIsMocked", isMocked)
            put("checkInOperationId", targetOpId)
        }.toString()

        val queueItem = SyncQueueEntity(
            operationId = targetOpId,
            entityType = "ATTENDANCE",
            entityId = targetAttendanceId,
            operationType = OperationType.CHECK_IN,
            payloadJson = payload,
            status = QueueStatus.PENDING
        )

        db.withTransaction {
            attendanceDao.insert(entity)
            syncQueueDao.insert(queueItem)
        }

        // Start location tracking service for shift
        LocationTrackingService.startTracking(context, userId)

        // Trigger immediate background sync
        SyncManager.triggerSync(context)

        return AttendanceWriteResult.Success(entity)
    }

    override suspend fun checkOut(
        attendanceId: String,
        userId: String,
        lat: Double,
        lng: Double,
        photoPath: String?,
        operationId: String?
    ): AttendanceWriteResult {
        val existing = attendanceDao.getById(attendanceId)
            ?: return AttendanceWriteResult.NotFound("Attendance record not found for id $attendanceId")

        // Reuse existing checkOutOperationId if already generated on a prior attempt
        val targetOpId = existing.checkOutOperationId ?: operationId ?: UUID.randomUUID().toString()
        val now = existing.checkOutAt ?: System.currentTimeMillis()

        // Part A.2: Check if this operation has already reached DEAD status
        val existingQueueItem = syncQueueDao.getByOperationId(targetOpId)
        if (existingQueueItem != null && existingQueueItem.status == QueueStatus.DEAD) {
            return AttendanceWriteResult.BlockedByDeadOperation(
                reason = existingQueueItem.lastError,
                operationId = targetOpId
            )
        }

        val updatedEntity = existing.copy(
            checkOutAt = now,
            checkOutLat = lat,
            checkOutLng = lng,
            checkOutPhotoPath = photoPath,
            checkOutOperationId = targetOpId,
            syncState = SyncState.PENDING
        )

        val payload = JSONObject().apply {
            put("id", attendanceId)
            put("userId", userId)
            put("checkOutAt", formatIsoUtc(now))
            put("checkOutLat", lat)
            put("checkOutLng", lng)
            put("checkOutPhotoPath", photoPath)
            put("checkOutOperationId", targetOpId)
        }.toString()

        val queueItem = SyncQueueEntity(
            operationId = targetOpId,
            entityType = "ATTENDANCE",
            entityId = attendanceId,
            operationType = OperationType.CHECK_OUT,
            payloadJson = payload,
            status = QueueStatus.PENDING
        )

        db.withTransaction {
            attendanceDao.update(updatedEntity)
            syncQueueDao.insert(queueItem)
        }

        // Stop location tracking service
        LocationTrackingService.stopTracking(context)

        // Trigger immediate background sync
        SyncManager.triggerSync(context)

        return AttendanceWriteResult.Success(updatedEntity)
    }

    override suspend fun hydrate(since: String?): Result<List<AttendanceEntity>> = runCatching {
        val response = apiService.getAttendanceHistory(since)
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to hydrate attendance: HTTP ${response.code()}")
        }
        val serverDtos = response.body() ?: emptyList()
        val serverEntities = serverDtos.map { it.toEntity() }

        val localEntities = attendanceDao.getAll()
        val pendingEntityIds = syncQueueDao.getPendingEntityIds().toSet()

        val merged = HydrationMergeGuard.merge(
            localEntities = localEntities,
            serverEntities = serverEntities,
            unsyncedQueueEntityIds = pendingEntityIds
        )

        db.withTransaction {
            attendanceDao.insertAll(merged)
        }

        merged
    }
}
