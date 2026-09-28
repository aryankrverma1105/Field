package com.sologix.attendance.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val syncQueueDao = db.syncQueueDao()

    /**
     * Phase 1 Item 10: StateFlow pipeline directly observing Room Flow.
     * When background SyncWorker modifies database, this StateFlow automatically
     * emits new state to Compose UI without any separate parallel in-memory cache.
     */
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

    fun triggerManualRetry() {
        SyncManager.triggerSync(getApplication())
    }
}
