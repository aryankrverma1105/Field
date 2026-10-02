package com.sologix.attendance.data.remote

import com.google.gson.annotations.SerializedName
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.SyncState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class AttendanceHistoryDto(
    @SerializedName("id") val id: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("check_in_at") val checkInAt: String? = null,
    @SerializedName("check_in_lat") val checkInLat: Double? = null,
    @SerializedName("check_in_lng") val checkInLng: Double? = null,
    @SerializedName("check_in_photo_path") val checkInPhotoPath: String? = null,
    @SerializedName("check_in_is_mocked") val checkInIsMocked: Any? = null,
    @SerializedName("check_in_operation_id") val checkInOperationId: String? = null,
    @SerializedName("check_out_at") val checkOutAt: String? = null,
    @SerializedName("check_out_lat") val checkOutLat: Double? = null,
    @SerializedName("check_out_lng") val checkOutLng: Double? = null,
    @SerializedName("check_out_photo_path") val checkOutPhotoPath: String? = null,
    @SerializedName("check_out_operation_id") val checkOutOperationId: String? = null,
    @SerializedName("geofence_status") val geofenceStatus: String? = "UNKNOWN",
    @SerializedName("created_at") val createdAt: String? = null
) {
    fun toEntity(): AttendanceEntity {
        fun parseIso(dateStr: String?): Long? {
            if (dateStr == null) return null
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(dateStr)?.time
            } catch (_: Exception) {
                try {
                    val sdfNoMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    sdfNoMs.parse(dateStr)?.time
                } catch (_: Exception) {
                    null
                }
            }
        }

        val checkInMs = parseIso(checkInAt) ?: System.currentTimeMillis()
        val checkOutMs = parseIso(checkOutAt)
        val createdMs = parseIso(createdAt) ?: checkInMs

        val isMocked = when (checkInIsMocked) {
            is Boolean -> checkInIsMocked
            is Number -> checkInIsMocked.toInt() == 1
            is String -> checkInIsMocked.equals("true", ignoreCase = true) || checkInIsMocked == "1"
            else -> false
        }

        return AttendanceEntity(
            id = id,
            userId = userId,
            checkInAt = checkInMs,
            checkInLat = checkInLat ?: 0.0,
            checkInLng = checkInLng ?: 0.0,
            checkInPhotoPath = checkInPhotoPath,
            checkInIsMocked = isMocked,
            checkInOperationId = checkInOperationId ?: "",
            checkOutAt = checkOutMs,
            checkOutLat = checkOutLat,
            checkOutLng = checkOutLng,
            checkOutPhotoPath = checkOutPhotoPath,
            checkOutOperationId = checkOutOperationId,
            geofenceStatus = geofenceStatus ?: "UNKNOWN",
            syncState = SyncState.SYNCED,
            createdAt = createdMs
        )
    }
}
