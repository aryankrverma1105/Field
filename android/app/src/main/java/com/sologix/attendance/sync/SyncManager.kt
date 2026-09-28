package com.sologix.attendance.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sologix.attendance.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Phase 1 Item 6 & Item 7: SyncManager coordinating all 4 triggers and crash-safety on startup.
 *
 * Triggers:
 * 1. Immediately after a local write (enqueueUniqueWork)
 * 2. On network-restored (NetworkType.CONNECTED constraint)
 * 3. On app foreground
 * 4. Periodic 15-minute backstop (WorkManager minimum interval)
 */
object SyncManager {

    /**
     * Phase 1 Item 7: Crash-safety on launch.
     * Before anything else runs, queries for any sync_queue row stuck in IN_PROGRESS
     * from an interrupted run and resets it to FAILED so the next worker picks it up.
     */
    suspend fun performCrashRecovery(db: AppDatabase): Int {
        return db.syncQueueDao().resetInProgressToFailed()
    }

    fun performCrashRecovery(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(context)
            val resetCount = performCrashRecovery(db)
            if (resetCount > 0) {
                // If any rows were recovered from stuck state, immediately trigger sync
                triggerSync(context)
            }
        }
    }

    /**
     * Trigger 1 & 2: One-time sync request with NetworkType.CONNECTED and Exponential Backoff.
     */
    fun triggerSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncRequest
        )
    }

    /**
     * Trigger 3: App foreground event.
     */
    fun onAppForegrounded(context: Context) {
        triggerSync(context)
    }

    /**
     * Trigger 4: Periodic 15-minute backstop sync.
     */
    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }
}
