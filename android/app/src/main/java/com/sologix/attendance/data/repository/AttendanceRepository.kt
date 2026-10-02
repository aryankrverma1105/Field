package com.sologix.attendance.data.repository

import com.sologix.attendance.data.local.entity.AttendanceEntity
import kotlinx.coroutines.flow.Flow

interface AttendanceRepository {
    fun observeLatest(): Flow<AttendanceEntity?>
    suspend fun getLatest(): AttendanceEntity?
    suspend fun getById(id: String): AttendanceEntity?

    suspend fun checkIn(
        userId: String,
        lat: Double,
        lng: Double,
        photoPath: String? = null,
        isMocked: Boolean = false,
        attendanceId: String? = null,
        operationId: String? = null
    ): AttendanceWriteResult

    suspend fun checkOut(
        attendanceId: String,
        userId: String,
        lat: Double,
        lng: Double,
        photoPath: String? = null,
        operationId: String? = null
    ): AttendanceWriteResult

    suspend fun hydrate(since: String? = null): Result<List<AttendanceEntity>>
}
