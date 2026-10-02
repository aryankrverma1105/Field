package com.sologix.attendance.data.remote

import com.google.gson.annotations.SerializedName
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.local.entity.VisitEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class VisitHistoryDto(
    @SerializedName("id") val id: String,
    @SerializedName("customer_id") val customerId: String,
    @SerializedName("assigned_to") val assignedTo: String,
    @SerializedName("scheduled_for") val scheduledFor: String? = null,
    @SerializedName("status") val status: String? = "SCHEDULED",
    @SerializedName("check_in_at") val checkInAt: String? = null,
    @SerializedName("check_in_operation_id") val checkInOperationId: String? = null,
    @SerializedName("check_out_at") val checkOutAt: String? = null,
    @SerializedName("complete_operation_id") val completeOperationId: String? = null,
    @SerializedName("meeting_outcome") val meetingOutcome: String? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("follow_up_date") val followUpDate: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
) {
    fun toEntity(): VisitEntity {
        val createdMs = parseIso(createdAt) ?: System.currentTimeMillis()
        return VisitEntity(
            id = id,
            customerId = customerId,
            assignedTo = assignedTo,
            scheduledFor = parseIso(scheduledFor),
            status = status ?: "SCHEDULED",
            checkInAt = parseIso(checkInAt),
            checkInOperationId = checkInOperationId,
            checkOutAt = parseIso(checkOutAt),
            completeOperationId = completeOperationId,
            meetingOutcome = meetingOutcome,
            notes = notes,
            followUpDate = followUpDate,
            syncState = SyncState.SYNCED,
            createdAt = createdMs
        )
    }

    private fun parseIso(dateStr: String?): Long? {
        if (dateStr == null) return null
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(dateStr)
                if (parsed != null) return parsed.time
            } catch (_: Exception) { }
        }
        return null
    }
}
