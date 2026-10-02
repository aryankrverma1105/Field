package com.sologix.attendance.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.sologix.attendance.data.hydration.HydrationMergeGuard
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.CustomerEntity
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
class CustomerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val apiService: ApiService
) : CustomerRepository {

    private val customerDao = db.customerDao()
    private val syncQueueDao = db.syncQueueDao()

    override fun observeAll(): Flow<List<CustomerEntity>> = customerDao.observeAll()

    override suspend fun getById(id: String): CustomerEntity? = customerDao.getById(id)

    override suspend fun createCustomer(
        name: String,
        phone: String?,
        address: String?,
        createdBy: String,
        customerId: String?,
        operationId: String?
    ): CustomerWriteResult {
        val existing = customerId?.let { customerDao.getById(it) }

        val targetCustomerId = existing?.id ?: customerId ?: UUID.randomUUID().toString()
        val targetOpId = existing?.operationId ?: operationId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Dead-letter check: block write if operation already reached DEAD status
        val existingQueueItem = syncQueueDao.getByOperationId(targetOpId)
        if (existingQueueItem != null && existingQueueItem.status == QueueStatus.DEAD) {
            return CustomerWriteResult.BlockedByDeadOperation(
                reason = existingQueueItem.lastError,
                operationId = targetOpId
            )
        }

        val entity = existing?.copy(
            name = name,
            phone = phone,
            address = address,
            syncState = SyncState.PENDING
        ) ?: CustomerEntity(
            id = targetCustomerId,
            name = name,
            phone = phone,
            address = address,
            createdBy = createdBy,
            operationId = targetOpId,
            syncState = SyncState.PENDING,
            createdAt = now
        )

        val payload = JSONObject().apply {
            put("id", targetCustomerId)
            put("name", name)
            put("phone", phone)
            put("address", address)
            put("operationId", targetOpId)
        }.toString()

        val queueItem = SyncQueueEntity(
            operationId = targetOpId,
            entityType = "CUSTOMER",
            entityId = targetCustomerId,
            operationType = OperationType.CUSTOMER_CREATE,
            payloadJson = payload,
            status = QueueStatus.PENDING
        )

        db.withTransaction {
            customerDao.insert(entity)
            syncQueueDao.insert(queueItem)
        }

        SyncManager.triggerSync(context)

        return CustomerWriteResult.Success(entity)
    }

    override suspend fun hydrate(since: String?): Result<List<CustomerEntity>> = runCatching {
        val response = apiService.getCustomerHistory(since)
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to hydrate customers: HTTP ${response.code()}")
        }
        val serverDtos = response.body() ?: emptyList()
        val serverEntities = serverDtos.map { it.toEntity() }

        val localEntities = customerDao.getAll()
        val pendingEntityIds = syncQueueDao.getPendingEntityIds().toSet()

        val merged = HydrationMergeGuard.merge(
            localEntities = localEntities,
            serverEntities = serverEntities,
            unsyncedQueueEntityIds = pendingEntityIds
        )

        db.withTransaction {
            customerDao.insertAll(merged)
        }

        merged
    }
}
