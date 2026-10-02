package com.sologix.attendance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sologix.attendance.data.hydration.HydrationMergeGuard
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.CustomerEntity
import com.sologix.attendance.data.local.entity.ExpenseEntity
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.QueueStatus
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.data.local.entity.TaskEntity
import com.sologix.attendance.data.local.entity.VisitEntity
import com.sologix.attendance.data.remote.ApiService
import com.sologix.attendance.data.remote.CustomerHistoryDto
import com.sologix.attendance.data.remote.ExpenseHistoryDto
import com.sologix.attendance.data.remote.TaskHistoryDto
import com.sologix.attendance.data.remote.VisitHistoryDto
import com.sologix.attendance.data.repository.CustomerRepositoryImpl
import com.sologix.attendance.data.repository.CustomerWriteResult
import com.sologix.attendance.data.repository.ExpenseRepositoryImpl
import com.sologix.attendance.data.repository.ExpenseWriteResult
import com.sologix.attendance.data.repository.TaskRepositoryImpl
import com.sologix.attendance.data.repository.TaskWriteResult
import com.sologix.attendance.data.repository.VisitRepositoryImpl
import com.sologix.attendance.data.repository.VisitWriteResult
import com.sologix.attendance.sync.DispatchResult
import com.sologix.attendance.sync.SyncWorker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
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
class DomainOfflineSyncAndHydrationTest {

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
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
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
    fun `Task local-first write creates entity and queue atomically and reuses operationId on retry`() = runBlocking {
        val repo = TaskRepositoryImpl(context, db, apiService)
        val taskId = "task-local-101"
        val opId = "op-task-101"

        // First attempt (offline write)
        val result1 = repo.createTask(
            title = "Inspect Site Alpha",
            description = "Check perimeter fence",
            assignedTo = "user-worker-1",
            assignedBy = "manager-1",
            scheduledDate = "2026-10-02",
            priority = "HIGH",
            taskId = taskId,
            operationId = opId
        )

        assertTrue("Expected Success result", result1 is TaskWriteResult.Success)
        val task = (result1 as TaskWriteResult.Success).task
        assertEquals(taskId, task.id)
        assertEquals(SyncState.PENDING, task.syncState)
        assertEquals(opId, task.operationId)

        // Verify local DB state
        val localTask = db.taskDao().getById(taskId)
        assertNotNull(localTask)
        assertEquals("Inspect Site Alpha", localTask?.title)

        val queueItem = db.syncQueueDao().getByOperationId(opId)
        assertNotNull(queueItem)
        assertEquals(QueueStatus.PENDING, queueItem?.status)
        assertEquals("TASK", queueItem?.entityType)
        assertEquals(OperationType.TASK_CREATE, queueItem?.operationType)

        // Retry with same taskId & opId should be idempotent locally
        val result2 = repo.createTask(
            title = "Inspect Site Alpha Updated",
            description = "Check perimeter fence",
            assignedTo = "user-worker-1",
            assignedBy = "manager-1",
            scheduledDate = "2026-10-02",
            priority = "HIGH",
            taskId = taskId,
            operationId = opId
        )
        assertTrue(result2 is TaskWriteResult.Success)
        assertEquals(opId, (result2 as TaskWriteResult.Success).task.operationId)
    }

    @Test
    fun `Task creation is blocked if operation has reached DEAD status`() = runBlocking {
        val repo = TaskRepositoryImpl(context, db, apiService)
        val opId = "op-dead-task"

        // Seed dead queue record
        val deadQueue = SyncQueueEntity(
            operationId = opId,
            entityType = "TASK",
            entityId = "task-dead-1",
            operationType = OperationType.TASK_CREATE,
            payloadJson = "{}",
            status = QueueStatus.DEAD,
            attemptCount = 5,
            lastError = "HTTP 400: Permanent validation failure"
        )
        db.syncQueueDao().insert(deadQueue)

        val result = repo.createTask(
            title = "Dead Task",
            description = null,
            assignedTo = "worker-1",
            assignedBy = "admin-1",
            taskId = "task-dead-1",
            operationId = opId
        )

        assertTrue("Expected BlockedByDeadOperation result", result is TaskWriteResult.BlockedByDeadOperation)
        val blocked = result as TaskWriteResult.BlockedByDeadOperation
        assertEquals("HTTP 400: Permanent validation failure", blocked.reason)
        assertEquals(opId, blocked.operationId)
    }

    @Test
    fun `SyncWorker dispatches TASK_CREATE mutation to backend endpoint successfully`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"success\":true}"))

        val worker = createWorker()
        val payload = "{\"id\":\"task-1\",\"assignedTo\":\"u-1\",\"title\":\"Fix pump\",\"operationId\":\"op-t1\"}"

        val result = worker.dispatchMutation(OperationType.TASK_CREATE, payload)
        assertTrue("Expected Success dispatch result", result is DispatchResult.Success)

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/api/tasks", request?.path)
        assertEquals("POST", request?.method)
        assertTrue(request?.body?.readUtf8()?.contains("Fix pump") == true)
    }

    @Test
    fun `Task hydration merges clean server tasks without overwriting dirty local task`() = runBlocking {
        val repo = TaskRepositoryImpl(context, db, apiService)

        // Local dirty task (PENDING sync)
        val localDirtyTask = TaskEntity(
            id = "task-dirty-1",
            assignedTo = "user-1",
            assignedBy = "mgr-1",
            title = "Local Offline Title",
            description = "Offline description",
            scheduledDate = "2026-10-02",
            operationId = "op-dirty-1",
            syncState = SyncState.PENDING
        )
        db.taskDao().insert(localDirtyTask)
        db.syncQueueDao().insert(
            SyncQueueEntity(
                operationId = "op-dirty-1",
                entityType = "TASK",
                entityId = "task-dirty-1",
                operationType = OperationType.TASK_CREATE,
                payloadJson = "{}",
                status = QueueStatus.PENDING
            )
        )

        // Mock server response containing server copy of task-dirty-1 and a new server task
        val serverJson = """
            [
              {
                "id": "task-dirty-1",
                "assigned_to": "user-1",
                "assigned_by": "mgr-1",
                "title": "Server Overwrite Attempt",
                "scheduled_date": "2026-10-02",
                "priority": "LOW",
                "status": "COMPLETED",
                "operation_id": "op-dirty-1",
                "updated_at": "2026-10-02T10:00:00.000Z"
              },
              {
                "id": "task-server-clean",
                "assigned_to": "user-1",
                "assigned_by": "mgr-1",
                "title": "Clean Server Task",
                "scheduled_date": "2026-10-03",
                "priority": "HIGH",
                "status": "PENDING",
                "operation_id": "op-server-2",
                "updated_at": "2026-10-02T10:00:00.000Z"
              }
            ]
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(serverJson))

        val hydrateResult = repo.hydrate()
        assertTrue(hydrateResult.isSuccess)
        val hydratedList = hydrateResult.getOrNull()
        assertNotNull(hydratedList)

        // Invariant: Dirty local task must NOT have been overwritten by server
        val finalDirtyTask = db.taskDao().getById("task-dirty-1")
        assertEquals("Local Offline Title", finalDirtyTask?.title)
        assertEquals(SyncState.PENDING, finalDirtyTask?.syncState)

        // Invariant: Clean server task is inserted with SYNCED state
        val cleanTask = db.taskDao().getById("task-server-clean")
        assertNotNull(cleanTask)
        assertEquals("Clean Server Task", cleanTask?.title)
        assertEquals(SyncState.SYNCED, cleanTask?.syncState)
    }

    @Test
    fun `Customer local-first write and dispatch round-trip`() = runBlocking {
        val repo = CustomerRepositoryImpl(context, db, apiService)
        val result = repo.createCustomer(
            name = "Acme Construction",
            phone = "+919876543210",
            address = "Industrial Zone Sector 5",
            createdBy = "user-1"
        )
        assertTrue(result is CustomerWriteResult.Success)
        val customer = (result as CustomerWriteResult.Success).customer
        assertEquals("Acme Construction", customer.name)
        assertEquals(SyncState.PENDING, customer.syncState)

        // Mock server response
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"success\":true}"))

        val worker = createWorker()
        val queueItem = db.syncQueueDao().getByOperationId(customer.operationId!!)
        assertNotNull(queueItem)

        val dispatchResult = worker.dispatchMutation(OperationType.CUSTOMER_CREATE, queueItem!!.payloadJson)
        assertTrue(dispatchResult is DispatchResult.Success)

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/api/customers", request?.path)
    }

    @Test
    fun `Visit split-key check-in and complete writes create independent queue operations`() = runBlocking {
        val repo = VisitRepositoryImpl(context, db, apiService)
        val visitId = "visit-split-1"
        val checkInOpId = "op-vis-checkin"
        val completeOpId = "op-vis-complete"

        // 1. Check-In
        val checkInResult = repo.checkIn(
            customerId = "cust-1",
            assignedTo = "user-1",
            visitId = visitId,
            operationId = checkInOpId
        )
        assertTrue(checkInResult is VisitWriteResult.Success)
        val v1 = (checkInResult as VisitWriteResult.Success).visit
        assertEquals(checkInOpId, v1.checkInOperationId)
        assertEquals(null, v1.completeOperationId)
        assertEquals("IN_PROGRESS", v1.status)

        val q1 = db.syncQueueDao().getByOperationId(checkInOpId)
        assertNotNull(q1)
        assertEquals(OperationType.VISIT_CHECK_IN, q1?.operationType)

        // 2. Complete (Split Key: independent completeOperationId)
        val completeResult = repo.complete(
            visitId = visitId,
            meetingOutcome = "Contract signed successfully",
            operationId = completeOpId
        )
        assertTrue(completeResult is VisitWriteResult.Success)
        val v2 = (completeResult as VisitWriteResult.Success).visit
        assertEquals(checkInOpId, v2.checkInOperationId)
        assertEquals(completeOpId, v2.completeOperationId)
        assertEquals("COMPLETED", v2.status)

        val q2 = db.syncQueueDao().getByOperationId(completeOpId)
        assertNotNull(q2)
        assertEquals(OperationType.VISIT_COMPLETE, q2?.operationType)

        // Test dispatch for both
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"success\":true}"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"success\":true}"))

        val worker = createWorker()
        val d1 = worker.dispatchMutation(OperationType.VISIT_CHECK_IN, q1!!.payloadJson)
        val d2 = worker.dispatchMutation(OperationType.VISIT_COMPLETE, q2!!.payloadJson)

        assertTrue(d1 is DispatchResult.Success)
        assertTrue(d2 is DispatchResult.Success)

        val r1 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        val r2 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/api/visits/check-in", r1?.path)
        assertEquals("/api/visits/complete", r2?.path)
    }

    @Test
    fun `Expense local-first write and dispatch round-trip`() = runBlocking {
        val repo = ExpenseRepositoryImpl(context, db, apiService)
        val result = repo.createExpense(
            userId = "user-1",
            amount = 1250.75,
            category = "TRAVEL_FUEL",
            receiptPhotoPath = "/storage/receipts/fuel_oct2.jpg"
        )
        assertTrue(result is ExpenseWriteResult.Success)
        val expense = (result as ExpenseWriteResult.Success).expense
        assertEquals(1250.75, expense.amount, 0.001)
        assertEquals("TRAVEL_FUEL", expense.category)
        assertEquals(SyncState.PENDING, expense.syncState)

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"success\":true}"))

        val worker = createWorker()
        val queueItem = db.syncQueueDao().getByOperationId(expense.operationId)
        assertNotNull(queueItem)
        assertEquals(OperationType.EXPENSE_CREATE, queueItem?.operationType)

        val dispatchResult = worker.dispatchMutation(OperationType.EXPENSE_CREATE, queueItem!!.payloadJson)
        assertTrue(dispatchResult is DispatchResult.Success)

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/api/expenses", request?.path)
        assertTrue(request?.body?.readUtf8()?.contains("1250.75") == true)
    }
}
