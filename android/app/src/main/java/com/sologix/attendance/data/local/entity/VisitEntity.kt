package com.sologix.attendance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Visit entity with independent idempotency keys for check-in vs complete.
 */
@Entity(tableName = "visits")
data class VisitEntity(
    @PrimaryKey
    override val id: String, // Stable client-generated UUID

    @ColumnInfo(name = "customer_id")
    val customerId: String,

    @ColumnInfo(name = "assigned_to")
    val assignedTo: String,

    @ColumnInfo(name = "scheduled_for")
    val scheduledFor: Long?,

    val status: String = "SCHEDULED",

    @ColumnInfo(name = "check_in_at")
    val checkInAt: Long? = null,

    @ColumnInfo(name = "check_in_operation_id")
    val checkInOperationId: String? = null,

    @ColumnInfo(name = "check_out_at")
    val checkOutAt: Long? = null,

    @ColumnInfo(name = "complete_operation_id")
    val completeOperationId: String? = null,

    @ColumnInfo(name = "meeting_outcome")
    val meetingOutcome: String? = null,

    val notes: String? = null,

    @ColumnInfo(name = "follow_up_date")
    val followUpDate: String? = null,

    @ColumnInfo(name = "sync_state")
    override val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) : SyncableEntity
