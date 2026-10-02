package com.sologix.attendance.data.repository

import com.sologix.attendance.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

sealed class TaskWriteResult {
    data class Success(val task: TaskEntity) : TaskWriteResult()
    data class BlockedByDeadOperation(val reason: String?, val operationId: String) : TaskWriteResult()
    data class NotFound(val message: String) : TaskWriteResult()
}

interface TaskRepository {
    fun observeAll(): Flow<List<TaskEntity>>
    suspend fun getById(id: String): TaskEntity?
    suspend fun createTask(
        title: String,
        description: String? = null,
        assignedTo: String,
        assignedBy: String,
        scheduledDate: String? = null,
        priority: String = "MEDIUM",
        taskId: String? = null,
        operationId: String? = null
    ): TaskWriteResult
    suspend fun hydrate(since: String? = null): Result<List<TaskEntity>>
}
