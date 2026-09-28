package com.sologix.attendance.data.local.entity

/**
 * Shared interface for entities participating in offline-sync and hydration merge guards.
 */
interface SyncableEntity {
    val id: String
    val syncState: SyncState
}
