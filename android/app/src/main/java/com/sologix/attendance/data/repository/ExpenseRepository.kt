package com.sologix.attendance.data.repository

import com.sologix.attendance.data.local.entity.ExpenseEntity
import kotlinx.coroutines.flow.Flow

sealed class ExpenseWriteResult {
    data class Success(val expense: ExpenseEntity) : ExpenseWriteResult()
    data class BlockedByDeadOperation(val reason: String?, val operationId: String) : ExpenseWriteResult()
    data class NotFound(val message: String) : ExpenseWriteResult()
}

interface ExpenseRepository {
    fun observeAll(): Flow<List<ExpenseEntity>>
    suspend fun getById(id: String): ExpenseEntity?
    suspend fun createExpense(
        userId: String,
        amount: Double,
        category: String,
        receiptPhotoPath: String? = null,
        expenseId: String? = null,
        operationId: String? = null
    ): ExpenseWriteResult
    suspend fun hydrate(since: String? = null): Result<List<ExpenseEntity>>
}
