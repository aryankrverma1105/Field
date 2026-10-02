package com.sologix.attendance.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.sologix.attendance.data.hydration.HydrationMergeGuard
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.local.entity.TaskEntity
import com.sologix.attendance.data.remote.ApiService
import com.sologix.attendance.sync.SyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val apiService: ApiService
) : TaskRepository {

    private val taskDao = db.taskDao()
    private val syncQueueDao = db.syncQueueDao()

    override fun observeAll(): Flow<List<TaskEntity>> = taskDao.observeAll()

    override suspend fun getById(id: String): TaskEntity? = taskDao.getById(id)

    override suspend fun createTask(
        title: String,
        description: String?,
        assignedTo: String,
        assignedBy: String,
        scheduledDate: String?,
        priority: String,
        taskId: String?,
        operationId: String?
    ): TaskWriteResult {
        val existing = taskId?.let { taskDao.getById(it) }

        val targetTaskId = existing?.id ?: taskId ?: UUID.randomUUID().toString()
        val targetOpId = existing?.operationId ?: operationId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Dead-letter check: block write if operation already reached DEAD status
        val existingQueueItem = syncQueueDao.getByOperationId(targetOpId)
        if (existingQueueItem != null && existingQueueItem.status == QueueStatus.DEAD) {
            return TaskWriteResult.BlockedByDeadOperation(
                reason = existingQueueItem.lastError,
                operationId = targetOpId
            )
        }

        val entity = existing?.copy(
            title = title,
            description = description,
            assignedTo = assignedTo,
            assignedBy = assignedBy,
            scheduledDate = scheduledDate,
            priority = priority,
            syncState = SyncState.PENDING,
            updatedAt = now
        ) ?: TaskEntity(
            id = targetTaskId,
            assignedTo = assignedTo,
            assignedBy = assignedBy,
            title = title,
            description = description,
            scheduledDate = scheduledDate,
            priority = priority,
            status = "PENDING",
            operationId = targetOpId,
            syncState = SyncState.PENDING,
            updatedAt = now
        )

        val payload = JSONObject().apply {
            put("id", targetTaskId)
            put("assignedTo", assignedTo)
            put("title", title)
            put("description", description)
            put("scheduledDate", scheduledDate)
            put("priority", priority)
            put("operationId", targetOpId)
        }.toString()

        val queueItem = SyncQueueEntity(
            operationId = targetOpId,
            entityType = "TASK",
            entityId = targetTaskId,
            operationType = OperationType.TASK_CREATE,
            payloadJson = payload,
            status = QueueStatus.PENDING
        )

        db.withTransaction {
            taskDao.insert(entity)
            syncQueueDao.insert(queueItem)
        }

        SyncManager.triggerSync(context)

        return TaskWriteResult.Success(entity)
    }

    override suspend fun hydrate(since: String?): Result<List<TaskEntity>> = runCatching {
        val response = apiService.getTaskHistory(since)
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to hydrate tasks: HTTP ${response.code()}")
        }
        val serverDtos = response.body() ?: emptyList()
        val serverEntities = serverDtos.map { it.toEntity() }

        val localEntities = taskDao.getAll()
        val pendingEntityIds = syncQueueDao.getPendingEntityIds().toSet()

        val merged = HydrationMergeGuard.merge(
            localEntities = localEntities,
            serverEntities = serverEntities,
            unsyncedQueueEntityIds = pendingEntityIds
        )

        db.withTransaction {
            taskDao.insertAll(merged)
        }

        merged
    }
}
