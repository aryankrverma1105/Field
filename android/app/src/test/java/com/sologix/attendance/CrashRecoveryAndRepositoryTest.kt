package com.sologix.attendance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.repository.AttendanceRepositoryImpl
import com.sologix.attendance.sync.SyncManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashRecoveryAndRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `crash recovery resets IN_PROGRESS row to FAILED in production database`() = runBlocking {
        val syncDao = db.syncQueueDao()
        val stuckItem = SyncQueueEntity(
            operationId = "op-crash-1",
            entityType = "ATTENDANCE",
            entityId = "att-1",
            operationType = OperationType.CHECK_IN,
            payloadJson = "{}",
            status = QueueStatus.IN_PROGRESS,
            attemptCount = 1
        )
        syncDao.insert(stuckItem)

        // Run production crash recovery suspend function
        val resetCount = SyncManager.performCrashRecovery(db)
        assertEquals(1, resetCount)

        // Verify via production DAO that the row is now FAILED
        val pendingOrFailed = syncDao.getPendingOrFailed()
        val recovered = pendingOrFailed.firstOrNull { it.operationId == "op-crash-1" }
        assertNotNull(recovered)
        assertEquals(QueueStatus.FAILED, recovered?.status)
    }

    @Test
    fun `AttendanceRepository checkIn writes entity and queue atomically and reuses operationId on retry`() = runBlocking {
        val repo = AttendanceRepositoryImpl(context, db)
        val attendanceId = "att-unique-123"
        val opId = "op-unique-456"

        // First attempt (checkIn)
        val entity1 = repo.checkIn(
            userId = "user-1",
            lat = 12.97,
            lng = 77.59,
            attendanceId = attendanceId,
            operationId = opId
        )

        assertEquals(attendanceId, entity1.id)
        assertEquals(opId, entity1.checkInOperationId)
        assertEquals(SyncState.PENDING, entity1.syncState)

        // Verify row in attendance table
        val savedEntity = db.attendanceDao().getById(attendanceId)
        assertNotNull(savedEntity)
        assertEquals(opId, savedEntity?.checkInOperationId)

        // Verify row in sync_queue table
        val pendingQueue = db.syncQueueDao().getPendingOrFailed()
        val queueItem = pendingQueue.firstOrNull { it.operationId == opId }
        assertNotNull(queueItem)
        assertEquals("ATTENDANCE", queueItem?.entityType)
        assertEquals(attendanceId, queueItem?.entityId)
        assertEquals(OperationType.CHECK_IN, queueItem?.operationType)

        // Retry path: call checkIn again for same attendanceId
        val entityRetry = repo.checkIn(
            userId = "user-1",
            lat = 12.97,
            lng = 77.59,
            attendanceId = attendanceId,
            operationId = "different-op-id-attempted"
        )

        // Must reuse the original checkInOperationId
        assertEquals(opId, entityRetry.checkInOperationId)
        val afterRetryEntity = db.attendanceDao().getById(attendanceId)
        assertEquals(opId, afterRetryEntity?.checkInOperationId)
    }
}
