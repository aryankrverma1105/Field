package com.sologix.attendance.data.hydration

import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.local.entity.SyncableEntity

object HydrationMergeGuard {

    /**
     * Merges server-hydrated entities with locally stored entities.
     *
     * Invariants:
     * 1. A server copy must NOT overwrite a local row if:
     *    - local row's syncState != SYNCED (i.e. PENDING, AWAITING_SERVER, FAILED), OR
     *    - local row's entity_id is present in [unsyncedQueueEntityIds] (PENDING, IN_PROGRESS, FAILED, DEAD).
     * 2. Local-only rows must survive hydration (not deleted/dropped).
     * 3. Server-only rows must be inserted into the result.
     * 4. Clean SYNCED rows (syncState == SYNCED and not in queue) are refreshed by the server copy.
     */
    fun <T : SyncableEntity> merge(
        localEntities: List<T>,
        serverEntities: List<T>,
        unsyncedQueueEntityIds: Set<String>
    ): List<T> {
        val localById = localEntities.associateBy { it.id }
        val serverById = serverEntities.associateBy { it.id }

        val result = mutableListOf<T>()

        // Process all existing local entities
        for (local in localEntities) {
            val isProtected = local.syncState != SyncState.SYNCED || unsyncedQueueEntityIds.contains(local.id)
            val serverMatch = serverById[local.id]

            if (serverMatch != null) {
                if (isProtected) {
                    // Local row is dirty or queued: local copy must NOT be overwritten
                    result.add(local)
                } else {
                    // Clean SYNCED row: safely refreshed by incoming server copy
                    result.add(serverMatch)
                }
            } else {
                // Local-only row: survives hydration
                result.add(local)
            }
        }

        // Process all server-only entities (not present in local DB)
        for (server in serverEntities) {
            if (!localById.containsKey(server.id)) {
                result.add(server)
            }
        }

        return result
    }
}
