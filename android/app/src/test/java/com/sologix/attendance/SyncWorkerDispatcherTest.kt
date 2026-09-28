package com.sologix.attendance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.remote.ApiService
import com.sologix.attendance.sync.SyncWorker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerDispatcherTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiService: ApiService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        mockWebServer = MockWebServer()
        mockWebServer.start()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        db.close()
    }

    private fun createWorker(): SyncWorker {
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return SyncWorker(appContext, workerParameters, db, apiService)
                }
            })
            .build()
    }

    @Test
    fun `200 response marks queue item and entity as SYNCED`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val att = AttendanceEntity(
            id = "att-1",
            userId = "user-1",
            checkInAt = 1000L,
            checkInLat = 12.0,
            checkInLng = 77.0,
            checkInOperationId = "op-200",
            syncState = SyncState.PENDING
        )
        db.attendanceDao().insert(att)

        val queueItem = SyncQueueEntity(
            operationId = "op-200",
            entityType = "ATTENDANCE",
            entityId = "att-1",
            operationType = OperationType.CHECK_IN,
            payloadJson = """{"id":"att-1","userId":"user-1","checkInAt":"2026-09-28T00:00:00.000Z","checkInLat":12.0,"checkInLng":77.0,"checkInOperationId":"op-200"}""",
            status = QueueStatus.PENDING
        )
        db.syncQueueDao().insert(queueItem)

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val allQueue = db.syncQueueDao().observeAll()
        // Queue status is SYNCED
        val itemInDb = db.syncQueueDao().getPendingOrFailed()
        assertTrue(itemInDb.none { it.operationId == "op-200" })

        val attInDb = db.attendanceDao().getById("att-1")
        assertEquals(SyncState.SYNCED, attInDb?.syncState)
    }

    @Test
    fun `200 alreadyProcessed response marks queue item and entity as SYNCED`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","alreadyProcessed":true}"""))

        val att = AttendanceEntity(
            id = "att-2",
            userId = "user-1",
            checkInAt = 1000L,
            checkInLat = 12.0,
            checkInLng = 77.0,
            checkInOperationId = "op-already",
            syncState = SyncState.PENDING
        )
        db.attendanceDao().insert(att)

        val queueItem = SyncQueueEntity(
            operationId = "op-already",
            entityType = "ATTENDANCE",
            entityId = "att-2",
            operationType = OperationType.CHECK_IN,
            payloadJson = """{"id":"att-2","userId":"user-1","checkInAt":"2026-09-28T00:00:00.000Z","checkInLat":12.0,"checkInLng":77.0,"checkInOperationId":"op-already"}""",
            status = QueueStatus.PENDING
        )
        db.syncQueueDao().insert(queueItem)

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val attInDb = db.attendanceDao().getById("att-2")
        assertEquals(SyncState.SYNCED, attInDb?.syncState)
    }

    @Test
    fun `500 response leaves row NOT synced and increments attempt count`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"DB Down"}"""))

        val queueItem = SyncQueueEntity(
            operationId = "op-500",
            entityType = "ATTENDANCE",
            entityId = "att-500",
            operationType = OperationType.CHECK_IN,
            payloadJson = """{"id":"att-500"}""",
            status = QueueStatus.PENDING,
            attemptCount = 0
        )
        db.syncQueueDao().insert(queueItem)

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val itemInDb = db.syncQueueDao().getPendingOrFailed().firstOrNull { it.operationId == "op-500" }
        assertNotNull(itemInDb)
        assertEquals(QueueStatus.FAILED, itemInDb?.status)
        assertEquals(1, itemInDb?.attemptCount)
    }

    @Test
    fun `network timeout or failure is transient and increments attempt`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val queueItem = SyncQueueEntity(
            operationId = "op-timeout",
            entityType = "ATTENDANCE",
            entityId = "att-timeout",
            operationType = OperationType.CHECK_IN,
            payloadJson = """{"id":"att-timeout"}""",
            status = QueueStatus.PENDING,
            attemptCount = 1
        )
        db.syncQueueDao().insert(queueItem)

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val itemInDb = db.syncQueueDao().getPendingOrFailed().firstOrNull { it.operationId == "op-timeout" }
        assertNotNull(itemInDb)
        assertEquals(QueueStatus.FAILED, itemInDb?.status)
        assertEquals(2, itemInDb?.attemptCount)
    }

    @Test
    fun `409 or 400 permanent error moves row directly to DEAD`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Conflict PK collision"}"""))

        val queueItem = SyncQueueEntity(
            operationId = "op-409",
            entityType = "ATTENDANCE",
            entityId = "att-409",
            operationType = OperationType.CHECK_IN,
            payloadJson = """{"id":"att-409"}""",
            status = QueueStatus.PENDING,
            attemptCount = 0
        )
        db.syncQueueDao().insert(queueItem)

        val worker = createWorker()
        val result = worker.doWork()

        // Excluded from getPendingOrFailed since it's now DEAD
        val pendingOrFailed = db.syncQueueDao().getPendingOrFailed()
        assertTrue(pendingOrFailed.none { it.operationId == "op-409" })
    }

    @Test
    fun `401 response does not increment attempt count and stops run`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Token expired"}"""))

        val queueItem = SyncQueueEntity(
            operationId = "op-401",
            entityType = "ATTENDANCE",
            entityId = "att-401",
            operationType = OperationType.CHECK_IN,
            payloadJson = """{"id":"att-401"}""",
            status = QueueStatus.PENDING,
            attemptCount = 2
        )
        db.syncQueueDao().insert(queueItem)

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val itemInDb = db.syncQueueDao().getPendingOrFailed().firstOrNull { it.operationId == "op-401" }
        assertNotNull(itemInDb)
        // Must NOT increment attempt_count on 401
        assertEquals(2, itemInDb?.attemptCount)
    }

    @Test
    fun `unsupported OperationType is never marked SYNCED`() = runBlocking {
        val queueItem = SyncQueueEntity(
            operationId = "op-unsupported",
            entityType = "CHAT",
            entityId = "chat-1",
            operationType = OperationType.CHAT_MESSAGE, // No endpoint exists yet
            payloadJson = """{"message":"hello"}""",
            status = QueueStatus.PENDING
        )
        db.syncQueueDao().insert(queueItem)

        val worker = createWorker()
        worker.doWork()

        // Item remains un-synced in PENDING state
        val itemInDb = db.syncQueueDao().getPendingOrFailed().firstOrNull { it.operationId == "op-unsupported" }
        assertNotNull(itemInDb)
        assertNotEquals(QueueStatus.SYNCED, itemInDb?.status)
    }

    @Test
    fun `reaching 5 failures moves row to DEAD`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"Persistent 500"}"""))

        val queueItem = SyncQueueEntity(
            operationId = "op-max-retry",
            entityType = "ATTENDANCE",
            entityId = "att-max",
            operationType = OperationType.CHECK_IN,
            payloadJson = """{"id":"att-max"}""",
            status = QueueStatus.FAILED,
            attemptCount = 4 // 4th attempt already; 5th failure should mark DEAD
        )
        db.syncQueueDao().insert(queueItem)

        val worker = createWorker()
        worker.doWork()

        // Row must now be DEAD and thus excluded from getPendingOrFailed
        val pendingOrFailed = db.syncQueueDao().getPendingOrFailed()
        assertTrue(pendingOrFailed.none { it.operationId == "op-max-retry" })
    }

    @Test
    fun `check-out is not dispatched before its check-in is SYNCED`() = runBlocking {
        val entityId = "att-ordered-seq"

        // Check-in item (older)
        val checkIn = SyncQueueEntity(
            operationId = "op-checkin",
            entityType = "ATTENDANCE",
            entityId = entityId,
            operationType = OperationType.CHECK_IN,
            payloadJson = """{"id":"$entityId"}""",
            status = QueueStatus.PENDING,
            createdAt = 1000L
        )

        // Check-out item (newer, same entity)
        val checkOut = SyncQueueEntity(
            operationId = "op-checkout",
            entityType = "ATTENDANCE",
            entityId = entityId,
            operationType = OperationType.CHECK_OUT,
            payloadJson = """{"id":"$entityId"}""",
            status = QueueStatus.PENDING,
            createdAt = 2000L
        )

        db.syncQueueDao().insert(checkIn)
        db.syncQueueDao().insert(checkOut)

        // Enqueue 500 error for check-in: check-in fails, check-out must NOT be dispatched!
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"Check-in temporary error"}"""))

        val worker = createWorker()
        worker.doWork()

        // Check-in failed and has attemptCount = 1
        val itemsAfterRun = db.syncQueueDao().getPendingOrFailed()
        val checkInAfter = itemsAfterRun.firstOrNull { it.operationId == "op-checkin" }
        val checkOutAfter = itemsAfterRun.firstOrNull { it.operationId == "op-checkout" }

        assertEquals(QueueStatus.FAILED, checkInAfter?.status)
        assertEquals(1, checkInAfter?.attemptCount)

        // Check-out was NOT sent and still has attemptCount = 0!
        assertEquals(QueueStatus.PENDING, checkOutAfter?.status)
        assertEquals(0, checkOutAfter?.attemptCount)
        assertEquals(1, mockWebServer.requestCount) // Only check-in was requested!
    }
}
