package com.sologix.attendance

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.sologix.attendance.sync.SyncManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SologixApplication : Application(), DefaultLifecycleObserver, Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super<Application>.onCreate()

        // 1. Crash safety on launch: reset any stuck IN_PROGRESS sync items to FAILED
        SyncManager.performCrashRecovery(this)

        // 2. Schedule 15-minute periodic backstop sync
        SyncManager.schedulePeriodicSync(this)

        // 3. Register lifecycle observer for app foreground sync trigger
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        // Trigger 3: App foregrounded
        SyncManager.onAppForegrounded(this)
    }
}
