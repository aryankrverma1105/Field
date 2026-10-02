package com.sologix.attendance.data.repository

import com.sologix.attendance.data.local.entity.AttendanceEntity

sealed class AttendanceWriteResult {
    data class Success(val attendance: AttendanceEntity) : AttendanceWriteResult()
    data class BlockedByDeadOperation(val reason: String?, val operationId: String) : AttendanceWriteResult()
    data class NotFound(val message: String) : AttendanceWriteResult()
}
