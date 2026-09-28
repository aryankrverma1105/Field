package com.sologix.attendance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String, // Stable client-generated UUID

    @ColumnInfo(name = "assigned_to")
    val assignedTo: String,

    @ColumnInfo(name = "assigned_by")
    val assignedBy: String,

    val title: String,
    val description: String?,

    @ColumnInfo(name = "scheduled_date")
    val scheduledDate: String?,

    val priority: String = "MEDIUM",
    val status: String = "PENDING",

    @ColumnInfo(name = "operation_id")
    val operationId: String?,

    @ColumnInfo(name = "sync_state")
    val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
