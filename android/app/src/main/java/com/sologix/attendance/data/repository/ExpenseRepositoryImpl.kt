package com.sologix.attendance.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.sologix.attendance.data.hydration.HydrationMergeGuard
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.ExpenseEntity
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.remote.ApiService
import com.sologix.attendance.sync.SyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpenseRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val apiService: ApiService
) : ExpenseRepository {

    private val expenseDao = db.expenseDao()
    private val syncQueueDao = db.syncQueueDao()

    override fun observeAll(): Flow<List<ExpenseEntity>> = expenseDao.observeAll()

    override suspend fun getById(id: String): ExpenseEntity? = expenseDao.getById(id)

    override suspend fun createExpense(
        userId: String,
        amount: Double,
        category: String,
        receiptPhotoPath: String?,
        expenseId: String?,
        operationId: String?
    ): ExpenseWriteResult {
        val existing = expenseId?.let { expenseDao.getById(it) }

        val targetExpenseId = existing?.id ?: expenseId ?: UUID.randomUUID().toString()
        val targetOpId = existing?.operationId ?: operationId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Dead-letter check: block write if operation already reached DEAD status
        val existingQueueItem = syncQueueDao.getByOperationId(targetOpId)
        if (existingQueueItem != null && existingQueueItem.status == QueueStatus.DEAD) {
            return ExpenseWriteResult.BlockedByDeadOperation(
                reason = existingQueueItem.lastError,
                operationId = targetOpId
            )
        }

        val entity = existing?.copy(
            amount = amount,
            category = category,
            receiptPhotoPath = receiptPhotoPath,
            syncState = SyncState.PENDING
        ) ?: ExpenseEntity(
            id = targetExpenseId,
            userId = userId,
            amount = amount,
            category = category,
            receiptPhotoPath = receiptPhotoPath,
            status = "PENDING",
            operationId = targetOpId,
            syncState = SyncState.PENDING,
            createdAt = now
        )

        val payload = JSONObject().apply {
            put("id", targetExpenseId)
            put("amount", amount)
            put("category", category)
            put("receiptPhotoPath", receiptPhotoPath)
            put("operationId", targetOpId)
        }.toString()

        val queueItem = SyncQueueEntity(
            operationId = targetOpId,
            entityType = "EXPENSE",
            entityId = targetExpenseId,
            operationType = OperationType.EXPENSE_CREATE,
            payloadJson = payload,
            status = QueueStatus.PENDING
        )

        db.withTransaction {
            expenseDao.insert(entity)
            syncQueueDao.insert(queueItem)
        }

        SyncManager.triggerSync(context)

        return ExpenseWriteResult.Success(entity)
    }

    override suspend fun hydrate(since: String?): Result<List<ExpenseEntity>> = runCatching {
        val response = apiService.getExpenseHistory(since)
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to hydrate expenses: HTTP ${response.code()}")
        }
        val serverDtos = response.body() ?: emptyList()
        val serverEntities = serverDtos.map { it.toEntity() }

        val localEntities = expenseDao.getAll()
        val pendingEntityIds = syncQueueDao.getPendingEntityIds().toSet()

        val merged = HydrationMergeGuard.merge(
            localEntities = localEntities,
            serverEntities = serverEntities,
            unsyncedQueueEntityIds = pendingEntityIds
        )

        db.withTransaction {
            expenseDao.insertAll(merged)
        }

        merged
    }
}
