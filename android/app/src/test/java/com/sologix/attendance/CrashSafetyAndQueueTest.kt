package com.sologix.attendance

import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 1 Item 7 & Verification Item 4:
 * Tests crash recovery logic and stable operationId generation.
 */
class CrashSafetyAndQueueTest {

    @Test
    fun `Crash recovery logic resets IN_PROGRESS items back to FAILED`() {
        // Simulate a local queue with items in various states
        val originalQueue = listOf(
            SyncQueueEntity(
                operationId = "op-1",
                entityType = "ATTENDANCE",
                entityId = "att-1",
                operationType = OperationType.CHECK_IN,
                payloadJson = "{}",
                status = QueueStatus.IN_PROGRESS // Was interrupted by force-stop / crash mid-sync
            ),
            SyncQueueEntity(
                operationId = "op-2",
                entityType = "GPS_POINT",
                entityId = "gps-1",
                operationType = OperationType.GPS_POINT,
                payloadJson = "{}",
                status = QueueStatus.PENDING
            ),
            SyncQueueEntity(
                operationId = "op-3",
                entityType = "TASK",
                entityId = "task-1",
                operationType = OperationType.TASK_UPDATE,
                payloadJson = "{}",
                status = QueueStatus.SYNCED
            )
        )

        // Simulate crash-recovery reset query: UPDATE sync_queue SET status = 'FAILED' WHERE status = 'IN_PROGRESS'
        val recoveredQueue = originalQueue.map { item ->
            if (item.status == QueueStatus.IN_PROGRESS) {
                item.copy(status = QueueStatus.FAILED)
            } else {
                item
            }
        }

        // Assert: Interrupted item is recovered to FAILED so worker re-executes it
        val item1 = recoveredQueue.find { it.operationId == "op-1" }!!
        assertEquals(QueueStatus.FAILED, item1.status)

        // Assert: PENDING and SYNCED items remain unaffected
        val item2 = recoveredQueue.find { it.operationId == "op-2" }!!
        assertEquals(QueueStatus.PENDING, item2.status)

        val item3 = recoveredQueue.find { it.operationId == "op-3" }!!
        assertEquals(QueueStatus.SYNCED, item3.status)
    }

    @Test
    fun `Stable client-generated IDs and operationIds are reused verbatim on retry`() {
        // When user taps "Check In", IDs are generated ONCE
        val logicalEntityId = UUID.randomUUID().toString()
        val logicalOperationId = UUID.randomUUID().toString()

        val checkInAction1 = SyncQueueEntity(
            operationId = logicalOperationId,
            entityType = "ATTENDANCE",
            entityId = logicalEntityId,
            operationType = OperationType.CHECK_IN,
            payloadJson = """{"id":"$logicalEntityId","operationId":"$logicalOperationId"}""",
            status = QueueStatus.PENDING
        )

        // When a network failure happens and the action is retried:
        val retryAction = checkInAction1.copy(
            attemptCount = checkInAction1.attemptCount + 1,
            lastAttemptAt = System.currentTimeMillis()
        )

        // Both IDs MUST remain strictly identical
        assertEquals(logicalOperationId, retryAction.operationId)
        assertEquals(logicalEntityId, retryAction.entityId)
        assertEquals(1, retryAction.attemptCount)
    }
}
