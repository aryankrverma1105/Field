package com.sologix.attendance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.remote.ApiService
import com.sologix.attendance.data.remote.AttendanceHistoryDto
import com.sologix.attendance.data.repository.AttendanceRepositoryImpl
import com.sologix.attendance.data.repository.AttendanceWriteResult
import com.sologix.attendance.sync.SyncManager
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashRecoveryAndRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private val fakeApiService = object : ApiService {
        var historyResponse: Response<List<AttendanceHistoryDto>> = Response.success(emptyList())

        override suspend fun checkIn(body: RequestBody): Response<ResponseBody> =
            Response.success(ResponseBody.create(null, "{}"))

        override suspend fun checkOut(body: RequestBody): Response<ResponseBody> =
            Response.success(ResponseBody.create(null, "{}"))

        override suspend fun sendGpsPoint(body: RequestBody): Response<ResponseBody> =
            Response.success(ResponseBody.create(null, "{}"))

        override suspend fun getAttendanceHistory(since: String?): Response<List<AttendanceHistoryDto>> =
            historyResponse

        override suspend fun login(request: com.sologix.attendance.data.remote.LoginRequest): Response<com.sologix.attendance.data.remote.LoginResponse> =
            Response.success(
                com.sologix.attendance.data.remote.LoginResponse(
                    "fake-jwt",
                    com.sologix.attendance.data.remote.UserDto("u1", "Test", "+919999999999", "employee", "ACTIVE")
                )
            )

        override suspend fun createTask(body: RequestBody): Response<ResponseBody> =
            Response.success(ResponseBody.create(null, "{}"))

        override suspend fun getTaskHistory(since: String?): Response<List<com.sologix.attendance.data.remote.TaskHistoryDto>> =
            Response.success(emptyList())

        override suspend fun createCustomer(body: RequestBody): Response<ResponseBody> =
            Response.success(ResponseBody.create(null, "{}"))

        override suspend fun getCustomerHistory(since: String?): Response<List<com.sologix.attendance.data.remote.CustomerHistoryDto>> =
            Response.success(emptyList())

        override suspend fun visitCheckIn(body: RequestBody): Response<ResponseBody> =
            Response.success(ResponseBody.create(null, "{}"))

        override suspend fun visitComplete(body: RequestBody): Response<ResponseBody> =
            Response.success(ResponseBody.create(null, "{}"))

        override suspend fun visitNotes(body: RequestBody): Response<ResponseBody> =
            Response.success(ResponseBody.create(null, "{}"))

        override suspend fun getVisitHistory(since: String?): Response<List<com.sologix.attendance.data.remote.VisitHistoryDto>> =
            Response.success(emptyList())

        override suspend fun createExpense(body: RequestBody): Response<ResponseBody> =
            Response.success(ResponseBody.create(null, "{}"))

        override suspend fun getExpenseHistory(since: String?): Response<List<com.sologix.attendance.data.remote.ExpenseHistoryDto>> =
            Response.success(emptyList())
    }

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
        val repo = AttendanceRepositoryImpl(context, db, fakeApiService)
        val attendanceId = "att-unique-123"
        val opId = "op-unique-456"

        // First attempt (checkIn)
        val writeResult1 = repo.checkIn(
            userId = "user-1",
            lat = 12.97,
            lng = 77.59,
            attendanceId = attendanceId,
            operationId = opId
        )

        assertTrue(writeResult1 is AttendanceWriteResult.Success)
        val entity1 = (writeResult1 as AttendanceWriteResult.Success).attendance

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
        val writeResultRetry = repo.checkIn(
            userId = "user-1",
            lat = 12.97,
            lng = 77.59,
            attendanceId = attendanceId,
            operationId = "different-op-id-attempted"
        )

        assertTrue(writeResultRetry is AttendanceWriteResult.Success)
        val entityRetry = (writeResultRetry as AttendanceWriteResult.Success).attendance

        // Must reuse the original checkInOperationId
        assertEquals(opId, entityRetry.checkInOperationId)
        val afterRetryEntity = db.attendanceDao().getById(attendanceId)
        assertEquals(opId, afterRetryEntity?.checkInOperationId)
    }

    @Test
    fun `Part A1 - hydrate wires real ApiService and HydrationMergeGuard without overwriting pending local rows`() = runBlocking {
        val repo = AttendanceRepositoryImpl(context, db, fakeApiService)

        // Local Row 1: PENDING syncState, NO matching queue id in sync_queue
        val pendingLocal = AttendanceEntity(
            id = "att_pending_1",
            userId = "user-1",
            checkInAt = 1000L,
            checkInLat = 10.0,
            checkInLng = 20.0,
            checkInOperationId = "op-local-pending",
            syncState = SyncState.PENDING,
            createdAt = 1000L
        )

        // Local Row 2: Clean SYNCED row
        val syncedLocal = AttendanceEntity(
            id = "att_synced_2",
            userId = "user-1",
            checkInAt = 2000L,
            checkInLat = 30.0,
            checkInLng = 40.0,
            checkInOperationId = "op-local-synced",
            syncState = SyncState.SYNCED,
            createdAt = 2000L
        )

        db.attendanceDao().insert(pendingLocal)
        db.attendanceDao().insert(syncedLocal)

        // Stale / modified server response for both rows
        val serverPendingDto = AttendanceHistoryDto(
            id = "att_pending_1",
            userId = "user-1",
            checkInAt = "2026-09-28T12:00:00.000Z",
            checkInLat = 99.9, // Stale server attempt to overwrite
            checkInLng = 99.9,
            checkInOperationId = "op-local-pending",
            geofenceStatus = "INSIDE",
            createdAt = "2026-09-28T12:00:00.000Z"
        )
        val serverSyncedDto = AttendanceHistoryDto(
            id = "att_synced_2",
            userId = "user-1",
            checkInAt = "2026-09-28T12:00:00.000Z",
            checkInLat = 77.7, // Legitimate server update
            checkInLng = 88.8,
            checkInOperationId = "op-local-synced",
            geofenceStatus = "INSIDE",
            createdAt = "2026-09-28T12:00:00.000Z"
        )

        fakeApiService.historyResponse = Response.success(listOf(serverPendingDto, serverSyncedDto))

        // Execute real repository hydrate()
        val hydrateResult = repo.hydrate()
        assertTrue(hydrateResult.isSuccess)

        // Verify in Room database
        val afterPending = db.attendanceDao().getById("att_pending_1")
        assertNotNull(afterPending)
        assertEquals(SyncState.PENDING, afterPending?.syncState)
        assertEquals(10.0, afterPending?.checkInLat ?: 0.0, 0.001) // Untouched!

        val afterSynced = db.attendanceDao().getById("att_synced_2")
        assertNotNull(afterSynced)
        assertEquals(SyncState.SYNCED, afterSynced?.syncState)
        assertEquals(77.7, afterSynced?.checkInLat ?: 0.0, 0.001) // Refreshed!
    }

    @Test
    fun `Part A2 - dead queue row blocks checkIn and prevents silent reset to PENDING`() = runBlocking {
        val repo = AttendanceRepositoryImpl(context, db, fakeApiService)
        val attendanceId = "att-dead-1"
        val opId = "op-dead-perm"
        val deadReason = "409 Conflict: session already closed by another device"

        // Seed an attendance row and a DEAD queue row
        val entity = AttendanceEntity(
            id = attendanceId,
            userId = "user-1",
            checkInAt = 1000L,
            checkInLat = 12.97,
            checkInLng = 77.59,
            checkInOperationId = opId,
            syncState = SyncState.FAILED,
            createdAt = 1000L
        )
        db.attendanceDao().insert(entity)

        val deadQueueRow = SyncQueueEntity(
            operationId = opId,
            entityType = "ATTENDANCE",
            entityId = attendanceId,
            operationType = OperationType.CHECK_IN,
            payloadJson = "{}",
            status = QueueStatus.DEAD,
            attemptCount = 5,
            lastError = deadReason
        )
        db.syncQueueDao().insert(deadQueueRow)

        // Attempt checkIn again with same IDs
        val result = repo.checkIn(
            userId = "user-1",
            lat = 12.97,
            lng = 77.59,
            attendanceId = attendanceId,
            operationId = opId
        )

        // Assert: result is BlockedByDeadOperation
        assertTrue("Result must be BlockedByDeadOperation", result is AttendanceWriteResult.BlockedByDeadOperation)
        val blocked = result as AttendanceWriteResult.BlockedByDeadOperation
        assertEquals(deadReason, blocked.reason)
        assertEquals(opId, blocked.operationId)

        // Assert: the queue row is STILL DEAD (not silently revived to PENDING with attempt_count 0)
        val queueInDb = db.syncQueueDao().getByOperationId(opId)
        assertNotNull(queueInDb)
        assertEquals(QueueStatus.DEAD, queueInDb?.status)
        assertEquals(5, queueInDb?.attemptCount)
        assertEquals(deadReason, queueInDb?.lastError)
    }
}
