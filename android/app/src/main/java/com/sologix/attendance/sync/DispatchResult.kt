package com.sologix.attendance.sync

sealed class DispatchResult {
    data object Success : DispatchResult()
    data class Transient(val reason: String) : DispatchResult()
    data class Permanent(val reason: String) : DispatchResult()
    data object AuthRequired : DispatchResult()
    data class Unsupported(val reason: String = "OperationType has no endpoint yet") : DispatchResult()
}
