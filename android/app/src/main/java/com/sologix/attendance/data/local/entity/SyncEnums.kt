package com.sologix.attendance.data.local.entity

/**
 * Closed set of mutation types for offline synchronization.
 * ADDITIVE ONLY — never rename or repurpose existing values.
 */
enum class OperationType {
    CHECK_IN,
    CHECK_OUT,
    GPS_POINT,
    TASK_CREATE,
    TASK_UPDATE,
    CUSTOMER_CREATE,
    CUSTOMER_UPDATE,
    VISIT_CREATE,
    VISIT_CHECK_IN,
    VISIT_COMPLETE,
    VISIT_UPDATE_NOTES,
    VISIT_ADD_EVIDENCE,
    EXPENSE_CREATE,
    CHAT_MESSAGE
}

enum class QueueStatus {
    PENDING,
    IN_PROGRESS,
    SYNCED,
    FAILED
}

enum class SyncState {
    PENDING,
    AWAITING_SERVER,
    SYNCED,
    FAILED
}
