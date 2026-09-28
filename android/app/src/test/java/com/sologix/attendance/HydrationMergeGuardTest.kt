package com.sologix.attendance

import com.sologix.attendance.data.hydration.HydrationMergeGuard
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.CustomerEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.local.entity.TaskEntity
import com.sologix.attendance.data.local.entity.VisitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HydrationMergeGuardTest {

    @Test
    fun `pending row survives and is not overwritten by server copy`() {
        val localRow = AttendanceEntity(
            id = "att-1",
            userId = "user-1",
            checkInAt = 1000L,
            checkInLat = 12.0,
            checkInLng = 77.0,
            checkInOperationId = "op-1",
            geofenceStatus = "UNKNOWN",
            syncState = SyncState.PENDING
        )
        val serverRow = AttendanceEntity(
            id = "att-1",
            userId = "user-1",
            checkInAt = 1000L,
            checkInLat = 99.0, // Server has modified data
            checkInLng = 99.0,
            checkInOperationId = "op-1",
            geofenceStatus = "INSIDE",
            syncState = SyncState.SYNCED
        )

        val merged = HydrationMergeGuard.merge(
            localEntities = listOf(localRow),
            serverEntities = listOf(serverRow),
            unsyncedQueueEntityIds = emptySet()
        )

        assertEquals(1, merged.size)
        // Local copy survives
        assertEquals(SyncState.PENDING, merged[0].syncState)
        assertEquals(12.0, (merged[0] as AttendanceEntity).checkInLat, 0.001)
    }

    @Test
    fun `clean SYNCED row is refreshed by server copy`() {
        val localRow = AttendanceEntity(
            id = "att-2",
            userId = "user-1",
            checkInAt = 1000L,
            checkInLat = 12.0,
            checkInLng = 77.0,
            checkInOperationId = "op-2",
            geofenceStatus = "UNKNOWN",
            syncState = SyncState.SYNCED
        )
        val serverRow = AttendanceEntity(
            id = "att-2",
            userId = "user-1",
            checkInAt = 1000L,
            checkInLat = 12.0,
            checkInLng = 77.0,
            checkInOperationId = "op-2",
            geofenceStatus = "INSIDE", // Computed on server
            syncState = SyncState.SYNCED
        )

        val merged = HydrationMergeGuard.merge(
            localEntities = listOf(localRow),
            serverEntities = listOf(serverRow),
            unsyncedQueueEntityIds = emptySet()
        )

        assertEquals(1, merged.size)
        // Refreshed with server copy
        assertEquals("INSIDE", (merged[0] as AttendanceEntity).geofenceStatus)
    }

    @Test
    fun `local-only row survives hydration`() {
        val localRow = TaskEntity(
            id = "task-local-1",
            assignedTo = "user-1",
            assignedBy = "admin-1",
            title = "Offline Created Task",
            description = null,
            scheduledDate = "2026-09-28",
            operationId = "op-task-1",
            syncState = SyncState.PENDING
        )

        val merged = HydrationMergeGuard.merge(
            localEntities = listOf(localRow),
            serverEntities = emptyList<TaskEntity>(),
            unsyncedQueueEntityIds = setOf("task-local-1")
        )

        assertEquals(1, merged.size)
        assertEquals("task-local-1", merged[0].id)
    }

    @Test
    fun `server-only row is inserted into local state`() {
        val serverRow = CustomerEntity(
            id = "cust-srv-1",
            name = "Acme Corp",
            phone = "1234567890",
            address = "Bangalore",
            createdBy = "admin-1",
            operationId = null,
            syncState = SyncState.SYNCED
        )

        val merged = HydrationMergeGuard.merge(
            localEntities = emptyList<CustomerEntity>(),
            serverEntities = listOf(serverRow),
            unsyncedQueueEntityIds = emptySet()
        )

        assertEquals(1, merged.size)
        assertEquals("cust-srv-1", merged[0].id)
        assertEquals("Acme Corp", (merged[0] as CustomerEntity).name)
    }

    @Test
    fun `row that is SYNCED but still has a queue entry survives without being overwritten`() {
        // Row is marked SYNCED locally, but a follow-up mutation (e.g. check-out) is in the queue
        val localVisit = VisitEntity(
            id = "visit-1",
            customerId = "cust-1",
            assignedTo = "user-1",
            scheduledFor = 12345L,
            notes = "Local offline notes",
            syncState = SyncState.SYNCED
        )
        val serverVisit = VisitEntity(
            id = "visit-1",
            customerId = "cust-1",
            assignedTo = "user-1",
            scheduledFor = 12345L,
            notes = "Old server notes",
            syncState = SyncState.SYNCED
        )

        // Queue contains an active entry for visit-1
        val activeQueueIds = setOf("visit-1")

        val merged = HydrationMergeGuard.merge(
            localEntities = listOf(localVisit),
            serverEntities = listOf(serverVisit),
            unsyncedQueueEntityIds = activeQueueIds
        )

        assertEquals(1, merged.size)
        assertEquals("Local offline notes", (merged[0] as VisitEntity).notes)
    }
}
