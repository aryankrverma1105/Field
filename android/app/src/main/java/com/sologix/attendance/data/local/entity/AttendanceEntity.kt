package com.sologix.attendance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Attendance entity supporting Fix 1: Split check-in and check-out operation IDs.
 * Every write is local-first with syncState tracking.
 */
@Entity(tableName = "attendance")
data class AttendanceEntity(
    @PrimaryKey
    val id: String, // Stable client-generated UUID

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "check_in_at")
    val checkInAt: Long,

    @ColumnInfo(name = "check_in_lat")
    val checkInLat: Double,

    @ColumnInfo(name = "check_in_lng")
    val checkInLng: Double,

    @ColumnInfo(name = "check_in_photo_path")
    val checkInPhotoPath: String? = null,

    @ColumnInfo(name = "check_in_is_mocked")
    val checkInIsMocked: Boolean = false,

    @ColumnInfo(name = "check_in_operation_id")
    val checkInOperationId: String,

    @ColumnInfo(name = "check_out_at")
    val checkOutAt: Long? = null,

    @ColumnInfo(name = "check_out_lat")
    val checkOutLat: Double? = null,

    @ColumnInfo(name = "check_out_lng")
    val checkOutLng: Double? = null,

    @ColumnInfo(name = "check_out_photo_path")
    val checkOutPhotoPath: String? = null,

    @ColumnInfo(name = "check_out_operation_id")
    val checkOutOperationId: String? = null,

    @ColumnInfo(name = "geofence_status")
    val geofenceStatus: String = "INSIDE",

    @ColumnInfo(name = "sync_state")
    val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
