package com.sologix.attendance.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.CustomerEntity
import com.sologix.attendance.data.local.entity.ExpenseEntity
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.TaskEntity
import com.sologix.attendance.data.local.entity.VisitEntity
import com.sologix.attendance.data.repository.AttendanceRepository
import com.sologix.attendance.data.repository.AttendanceWriteResult
import com.sologix.attendance.data.repository.CustomerRepository
import com.sologix.attendance.data.repository.CustomerWriteResult
import com.sologix.attendance.data.repository.ExpenseRepository
import com.sologix.attendance.data.repository.ExpenseWriteResult
import com.sologix.attendance.data.repository.TaskRepository
import com.sologix.attendance.data.repository.TaskWriteResult
import com.sologix.attendance.data.repository.VisitRepository
import com.sologix.attendance.data.repository.VisitWriteResult
import com.sologix.attendance.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    application: Application,
    private val attendanceRepository: AttendanceRepository,
    private val taskRepository: TaskRepository,
    private val customerRepository: CustomerRepository,
    private val visitRepository: VisitRepository,
    private val expenseRepository: ExpenseRepository,
    private val db: AppDatabase
) : AndroidViewModel(application) {

    private val syncQueueDao = db.syncQueueDao()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    init {
        refreshHydration()
    }

    fun clearError() {
        _uiError.value = null
    }

    fun refreshHydration() {
        viewModelScope.launch {
            _isRefreshing.value = true
            attendanceRepository.hydrate()
                .onFailure { err -> _uiError.value = "Attendance hydration failed: ${err.message}" }
            taskRepository.hydrate()
                .onFailure { err -> _uiError.value = "Task hydration failed: ${err.message}" }
            customerRepository.hydrate()
                .onFailure { err -> _uiError.value = "Customer hydration failed: ${err.message}" }
            visitRepository.hydrate()
                .onFailure { err -> _uiError.value = "Visit hydration failed: ${err.message}" }
            expenseRepository.hydrate()
                .onFailure { err -> _uiError.value = "Expense hydration failed: ${err.message}" }
            _isRefreshing.value = false
        }
    }

    val queueItems: StateFlow<List<SyncQueueEntity>> = syncQueueDao.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val pendingCount: StateFlow<Int> = syncQueueDao.observePendingCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = 0
        )

    val latestAttendance: StateFlow<AttendanceEntity?> = attendanceRepository.observeLatest()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = null
        )

    val tasks: StateFlow<List<TaskEntity>> = taskRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val customers: StateFlow<List<CustomerEntity>> = customerRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val visits: StateFlow<List<VisitEntity>> = visitRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val expenses: StateFlow<List<ExpenseEntity>> = expenseRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    fun triggerManualRetry() {
        SyncManager.triggerSync(getApplication())
    }

    fun retryItem(operationId: String) {
        viewModelScope.launch {
            syncQueueDao.retryDead(operationId)
            SyncManager.triggerSync(getApplication())
        }
    }

    fun simulateCheckIn(userId: String = "emp_simulated_user") {
        viewModelScope.launch {
            when (val res = attendanceRepository.checkIn(
                userId = userId,
                lat = 12.9716,
                lng = 77.5946,
                photoPath = "debug://selfie_mock.jpg",
                isMocked = false
            )) {
                is AttendanceWriteResult.BlockedByDeadOperation -> {
                    _uiError.value = "Check-in blocked by dead operation: ${res.reason ?: "Server permanently rejected this check-in."}"
                }
                is AttendanceWriteResult.Success -> {
                    _uiError.value = null
                }
                is AttendanceWriteResult.NotFound -> {
                    _uiError.value = res.message
                }
            }
        }
    }

    fun simulateCheckOut(userId: String = "emp_simulated_user") {
        viewModelScope.launch {
            val latest = attendanceRepository.getLatest()
            if (latest != null && latest.checkOutAt == null) {
                when (val res = attendanceRepository.checkOut(
                    attendanceId = latest.id,
                    userId = userId,
                    lat = 12.9716,
                    lng = 77.5946,
                    photoPath = "debug://checkout_mock.jpg"
                )) {
                    is AttendanceWriteResult.BlockedByDeadOperation -> {
                        _uiError.value = "Check-out blocked by dead operation: ${res.reason ?: "Server permanently rejected this check-out."}"
                    }
                    is AttendanceWriteResult.Success -> {
                        _uiError.value = null
                    }
                    is AttendanceWriteResult.NotFound -> {
                        _uiError.value = res.message
                    }
                }
            }
        }
    }

    fun createTask(title: String, description: String? = null, assignedTo: String = "user_worker_1", priority: String = "MEDIUM") {
        viewModelScope.launch {
            when (val res = taskRepository.createTask(
                title = title,
                description = description,
                assignedTo = assignedTo,
                assignedBy = "self",
                priority = priority
            )) {
                is TaskWriteResult.BlockedByDeadOperation -> {
                    _uiError.value = "Task blocked by dead operation: ${res.reason}"
                }
                is TaskWriteResult.Success -> {
                    _uiError.value = null
                }
                is TaskWriteResult.NotFound -> {
                    _uiError.value = res.message
                }
            }
        }
    }

    fun createCustomer(name: String, phone: String? = null, address: String? = null) {
        viewModelScope.launch {
            when (val res = customerRepository.createCustomer(
                name = name,
                phone = phone,
                address = address,
                createdBy = "self"
            )) {
                is CustomerWriteResult.BlockedByDeadOperation -> {
                    _uiError.value = "Customer blocked by dead operation: ${res.reason}"
                }
                is CustomerWriteResult.Success -> {
                    _uiError.value = null
                }
                is CustomerWriteResult.NotFound -> {
                    _uiError.value = res.message
                }
            }
        }
    }

    fun checkInVisit(customerId: String, assignedTo: String = "self") {
        viewModelScope.launch {
            when (val res = visitRepository.checkIn(
                customerId = customerId,
                assignedTo = assignedTo
            )) {
                is VisitWriteResult.BlockedByDeadOperation -> {
                    _uiError.value = "Visit check-in blocked: ${res.reason}"
                }
                is VisitWriteResult.Success -> {
                    _uiError.value = null
                }
                is VisitWriteResult.NotFound -> {
                    _uiError.value = res.message
                }
            }
        }
    }

    fun completeVisit(visitId: String, outcome: String?) {
        viewModelScope.launch {
            when (val res = visitRepository.complete(
                visitId = visitId,
                meetingOutcome = outcome
            )) {
                is VisitWriteResult.BlockedByDeadOperation -> {
                    _uiError.value = "Visit completion blocked: ${res.reason}"
                }
                is VisitWriteResult.Success -> {
                    _uiError.value = null
                }
                is VisitWriteResult.NotFound -> {
                    _uiError.value = res.message
                }
            }
        }
    }

    fun createExpense(amount: Double, category: String, receiptPath: String? = null) {
        viewModelScope.launch {
            when (val res = expenseRepository.createExpense(
                userId = "self",
                amount = amount,
                category = category,
                receiptPhotoPath = receiptPath
            )) {
                is ExpenseWriteResult.BlockedByDeadOperation -> {
                    _uiError.value = "Expense blocked by dead operation: ${res.reason}"
                }
                is ExpenseWriteResult.Success -> {
                    _uiError.value = null
                }
                is ExpenseWriteResult.NotFound -> {
                    _uiError.value = res.message
                }
            }
        }
    }
}
