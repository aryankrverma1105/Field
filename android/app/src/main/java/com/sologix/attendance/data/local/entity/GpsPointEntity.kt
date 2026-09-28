package com.sologix.attendance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gps_points")
data class GpsPointEntity(
    @PrimaryKey
    val id: String, // Stable client-generated UUID

    @ColumnInfo(name = "user_id")
    val userId: String,

    val lat: Double,
    val lng: Double,

    @ColumnInfo(name = "is_mocked")
    val isMocked: Boolean = false,

    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long,

    @ColumnInfo(name = "operation_id")
    val operationId: String,

    @ColumnInfo(name = "sync_state")
    val syncState: SyncState = SyncState.PENDING
)
