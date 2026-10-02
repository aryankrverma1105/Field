package com.sologix.attendance.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.sologix.attendance.data.hydration.HydrationMergeGuard
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.local.entity.VisitEntity
import com.sologix.attendance.data.remote.ApiService
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
class VisitRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val apiService: ApiService
) : VisitRepository {

    private val visitDao = db.visitDao()
    private val syncQueueDao = db.syncQueueDao()

    override fun observeAll(): Flow<List<VisitEntity>> = visitDao.observeAll()

    override suspend fun getById(id: String): VisitEntity? = visitDao.getById(id)

    private fun formatIsoUtc(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMs))
    }

    override suspend fun checkIn(
        customerId: String,
        assignedTo: String,
        visitId: String?,
        operationId: String?
    ): VisitWriteResult {
        val existing = visitId?.let { visitDao.getById(it) }

        val targetVisitId = existing?.id ?: visitId ?: UUID.randomUUID().toString()
        val targetOpId = existing?.checkInOperationId ?: operationId ?: UUID.randomUUID().toString()
        val now = existing?.checkInAt ?: System.currentTimeMillis()

        // Dead-letter check: block write if operation already reached DEAD status
        val existingQueueItem = syncQueueDao.getByOperationId(targetOpId)
        if (existingQueueItem != null && existingQueueItem.status == QueueStatus.DEAD) {
            return VisitWriteResult.BlockedByDeadOperation(
                reason = existingQueueItem.lastError,
                operationId = targetOpId
            )
        }

        val entity = existing?.copy(
            checkInAt = now,
            checkInOperationId = targetOpId,
            status = "IN_PROGRESS",
            syncState = SyncState.PENDING
        ) ?: VisitEntity(
            id = targetVisitId,
            customerId = customerId,
            assignedTo = assignedTo,
            scheduledFor = now,
            status = "IN_PROGRESS",
            checkInAt = now,
            checkInOperationId = targetOpId,
            syncState = SyncState.PENDING,
            createdAt = now
        )

        val payload = JSONObject().apply {
            put("id", targetVisitId)
            put("customerId", customerId)
            put("checkInAt", formatIsoUtc(now))
            put("operationId", targetOpId)
        }.toString()

        val queueItem = SyncQueueEntity(
            operationId = targetOpId,
            entityType = "VISIT",
            entityId = targetVisitId,
            operationType = OperationType.VISIT_CHECK_IN,
            payloadJson = payload,
            status = QueueStatus.PENDING
        )

        db.withTransaction {
            visitDao.insert(entity)
            syncQueueDao.insert(queueItem)
        }

        SyncManager.triggerSync(context)

        return VisitWriteResult.Success(entity)
    }

    override suspend fun complete(
        visitId: String,
        meetingOutcome: String?,
        operationId: String?
    ): VisitWriteResult {
        val existing = visitDao.getById(visitId)
            ?: return VisitWriteResult.NotFound("Visit $visitId not found")

        val targetOpId = existing.completeOperationId ?: operationId ?: UUID.randomUUID().toString()
        val now = existing.checkOutAt ?: System.currentTimeMillis()

        // Dead-letter check
        val existingQueueItem = syncQueueDao.getByOperationId(targetOpId)
        if (existingQueueItem != null && existingQueueItem.status == QueueStatus.DEAD) {
            return VisitWriteResult.BlockedByDeadOperation(
                reason = existingQueueItem.lastError,
                operationId = targetOpId
            )
        }

        val updatedEntity = existing.copy(
            checkOutAt = now,
            completeOperationId = targetOpId,
            status = "COMPLETED",
            meetingOutcome = meetingOutcome ?: existing.meetingOutcome,
            syncState = SyncState.PENDING
        )

        val payload = JSONObject().apply {
            put("id", visitId)
            put("checkOutAt", formatIsoUtc(now))
            put("operationId", targetOpId)
            put("meetingOutcome", meetingOutcome)
        }.toString()

        val queueItem = SyncQueueEntity(
            operationId = targetOpId,
            entityType = "VISIT",
            entityId = visitId,
            operationType = OperationType.VISIT_COMPLETE,
            payloadJson = payload,
            status = QueueStatus.PENDING
        )

        db.withTransaction {
            visitDao.update(updatedEntity)
            syncQueueDao.insert(queueItem)
        }

        SyncManager.triggerSync(context)

        return VisitWriteResult.Success(updatedEntity)
    }

    override suspend fun updateNotes(
        visitId: String,
        notes: String?,
        meetingOutcome: String?,
        followUpDate: String?
    ): VisitWriteResult {
        val existing = visitDao.getById(visitId)
            ?: return VisitWriteResult.NotFound("Visit $visitId not found")

        val targetOpId = UUID.randomUUID().toString()

        val updatedEntity = existing.copy(
            notes = notes,
            meetingOutcome = meetingOutcome,
            followUpDate = followUpDate,
            syncState = SyncState.PENDING
        )

        val payload = JSONObject().apply {
            put("id", visitId)
            put("notes", notes)
            put("meetingOutcome", meetingOutcome)
            put("followUpDate", followUpDate)
            put("operationId", targetOpId)
        }.toString()

        val queueItem = SyncQueueEntity(
            operationId = targetOpId,
            entityType = "VISIT",
            entityId = visitId,
            operationType = OperationType.VISIT_UPDATE_NOTES,
            payloadJson = payload,
            status = QueueStatus.PENDING
        )

        db.withTransaction {
            visitDao.update(updatedEntity)
            syncQueueDao.insert(queueItem)
        }

        SyncManager.triggerSync(context)

        return VisitWriteResult.Success(updatedEntity)
    }

    override suspend fun hydrate(since: String?): Result<List<VisitEntity>> = runCatching {
        val response = apiService.getVisitHistory(since)
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to hydrate visits: HTTP ${response.code()}")
        }
        val serverDtos = response.body() ?: emptyList()
        val serverEntities = serverDtos.map { it.toEntity() }

        val localEntities = visitDao.getAll()
        val pendingEntityIds = syncQueueDao.getPendingEntityIds().toSet()

        val merged = HydrationMergeGuard.merge(
            localEntities = localEntities,
            serverEntities = serverEntities,
            unsyncedQueueEntityIds = pendingEntityIds
        )

        db.withTransaction {
            visitDao.insertAll(merged)
        }

        merged
    }
}
