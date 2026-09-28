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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    application: Application,
    private val attendanceRepository: AttendanceRepository,
    private val db: AppDatabase
) : AndroidViewModel(application) {

    private val syncQueueDao = db.syncQueueDao()

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
            attendanceRepository.checkIn(
                userId = userId,
                lat = 12.9716,
                lng = 77.5946,
                photoPath = "debug://selfie_mock.jpg",
                isMocked = false
            )
        }
    }

    fun simulateCheckOut(userId: String = "emp_simulated_user") {
        viewModelScope.launch {
            val latest = attendanceRepository.getLatest()
            if (latest != null && latest.checkOutAt == null) {
                attendanceRepository.checkOut(
                    attendanceId = latest.id,
                    userId = userId,
                    lat = 12.9716,
                    lng = 77.5946,
                    photoPath = "debug://checkout_mock.jpg"
                )
            }
        }
    }
}
