package com.sologix.attendance.data.remote

import com.google.gson.annotations.SerializedName
import com.sologix.attendance.data.local.entity.ExpenseEntity
import com.sologix.attendance.data.local.entity.SyncState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ExpenseHistoryDto(
    @SerializedName("id") val id: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("category") val category: String,
    @SerializedName("receipt_photo_path") val receiptPhotoPath: String? = null,
    @SerializedName("status") val status: String? = "PENDING",
    @SerializedName("reviewed_by") val reviewedBy: String? = null,
    @SerializedName("operation_id") val operationId: String,
    @SerializedName("created_at") val createdAt: String? = null
) {
    fun toEntity(): ExpenseEntity {
        val createdMs = parseIso(createdAt) ?: System.currentTimeMillis()
        return ExpenseEntity(
            id = id,
            userId = userId,
            amount = amount,
            category = category,
            receiptPhotoPath = receiptPhotoPath,
            status = status ?: "PENDING",
            reviewedBy = reviewedBy,
            operationId = operationId,
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
