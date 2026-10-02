package com.sologix.attendance.data.repository

import com.sologix.attendance.data.local.entity.CustomerEntity
import kotlinx.coroutines.flow.Flow

sealed class CustomerWriteResult {
    data class Success(val customer: CustomerEntity) : CustomerWriteResult()
    data class BlockedByDeadOperation(val reason: String?, val operationId: String) : CustomerWriteResult()
    data class NotFound(val message: String) : CustomerWriteResult()
}

interface CustomerRepository {
    fun observeAll(): Flow<List<CustomerEntity>>
    suspend fun getById(id: String): CustomerEntity?
    suspend fun createCustomer(
        name: String,
        phone: String? = null,
        address: String? = null,
        createdBy: String,
        customerId: String? = null,
        operationId: String? = null
    ): CustomerWriteResult
    suspend fun hydrate(since: String? = null): Result<List<CustomerEntity>>
}
