package com.sologix.attendance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey
    override val id: String, // Stable client-generated UUID

    val name: String,
    val phone: String?,
    val address: String?,

    @ColumnInfo(name = "created_by")
    val createdBy: String,

    @ColumnInfo(name = "operation_id")
    val operationId: String?,

    @ColumnInfo(name = "sync_state")
    override val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) : SyncableEntity
