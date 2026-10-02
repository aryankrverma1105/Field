package com.sologix.attendance.data.repository

import com.sologix.attendance.data.local.entity.VisitEntity
import kotlinx.coroutines.flow.Flow

sealed class VisitWriteResult {
    data class Success(val visit: VisitEntity) : VisitWriteResult()
    data class BlockedByDeadOperation(val reason: String?, val operationId: String) : VisitWriteResult()
    data class NotFound(val message: String) : VisitWriteResult()
}

interface VisitRepository {
    fun observeAll(): Flow<List<VisitEntity>>
    suspend fun getById(id: String): VisitEntity?
    suspend fun checkIn(
        customerId: String,
        assignedTo: String,
        visitId: String? = null,
        operationId: String? = null
    ): VisitWriteResult
    suspend fun complete(
        visitId: String,
        meetingOutcome: String? = null,
        operationId: String? = null
    ): VisitWriteResult
    suspend fun updateNotes(
        visitId: String,
        notes: String?,
        meetingOutcome: String?,
        followUpDate: String?
    ): VisitWriteResult
    suspend fun hydrate(since: String? = null): Result<List<VisitEntity>>
}
