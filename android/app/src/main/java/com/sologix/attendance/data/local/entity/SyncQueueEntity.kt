package com.sologix.attendance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 1 Item 3: Room schema for the offline queue.
 *
 * Dedicated table tracking every local mutation in sequence.
 * operation_id is generated once per logical user action and reused verbatim on retries.
 */
@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    @ColumnInfo(name = "operation_type")
    val operationType: OperationType,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "status")
    val status: QueueStatus = QueueStatus.PENDING,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
