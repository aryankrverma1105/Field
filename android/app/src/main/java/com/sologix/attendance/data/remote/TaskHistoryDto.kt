package com.sologix.attendance.data.remote

import com.google.gson.annotations.SerializedName
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.local.entity.TaskEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class TaskHistoryDto(
    @SerializedName("id") val id: String,
    @SerializedName("assigned_to") val assignedTo: String,
    @SerializedName("assigned_by") val assignedBy: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("scheduled_date") val scheduledDate: String? = null,
    @SerializedName("priority") val priority: String? = "MEDIUM",
    @SerializedName("status") val status: String? = "PENDING",
    @SerializedName("operation_id") val operationId: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
) {
    fun toEntity(): TaskEntity {
        val updatedMs = parseIso(updatedAt) ?: parseIso(createdAt) ?: System.currentTimeMillis()
        return TaskEntity(
            id = id,
            assignedTo = assignedTo,
            assignedBy = assignedBy,
            title = title,
            description = description,
            scheduledDate = scheduledDate,
            priority = priority ?: "MEDIUM",
            status = status ?: "PENDING",
            operationId = operationId,
            syncState = SyncState.SYNCED,
            updatedAt = updatedMs
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
