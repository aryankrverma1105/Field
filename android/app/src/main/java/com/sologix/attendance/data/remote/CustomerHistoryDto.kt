package com.sologix.attendance.data.remote

import com.google.gson.annotations.SerializedName
import com.sologix.attendance.data.local.entity.CustomerEntity
import com.sologix.attendance.data.local.entity.SyncState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class CustomerHistoryDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("created_by") val createdBy: String,
    @SerializedName("operation_id") val operationId: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
) {
    fun toEntity(): CustomerEntity {
        val createdMs = parseIso(createdAt) ?: System.currentTimeMillis()
        return CustomerEntity(
            id = id,
            name = name,
            phone = phone,
            address = address,
            createdBy = createdBy,
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
