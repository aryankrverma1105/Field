package com.sologix.attendance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey
    override val id: String, // Stable client-generated UUID

    @ColumnInfo(name = "user_id")
    val userId: String,

    val amount: Double,
    val category: String,

    @ColumnInfo(name = "receipt_photo_path")
    val receiptPhotoPath: String? = null,

    val status: String = "PENDING",

    @ColumnInfo(name = "reviewed_by")
    val reviewedBy: String? = null,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "sync_state")
    override val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) : SyncableEntity
