package com.sologix.attendance.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.repository.AttendanceRepository
import com.sologix.attendance.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.sologix.attendance.data.repository.AttendanceWriteResult

@HiltViewModel
class SyncViewModel @Inject constructor(
    application: Application,
    private val attendanceRepository: AttendanceRepository,
    private val db: AppDatabase
) : AndroidViewModel(application) {

    private val syncQueueDao = db.syncQueueDao()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    init {
        // Part A.1: Call hydrate() on app start
        refreshHydration()
    }

    fun clearError() {
        _uiError.value = null
    }

    fun refreshHydration() {
        viewModelScope.launch {
            _isRefreshing.value = true
            attendanceRepository.hydrate()
                .onFailure { err ->
                    _uiError.value = "Hydration failed: ${err.message}"
                }
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
                    _uiError.value = "Check-in blocked by dead operation: ${res.reason ?: "Server permanently rejected this check-in. Contact admin."}"
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
                        _uiError.value = "Check-out blocked by dead operation: ${res.reason ?: "Server permanently rejected this check-out. Contact admin."}"
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
}
