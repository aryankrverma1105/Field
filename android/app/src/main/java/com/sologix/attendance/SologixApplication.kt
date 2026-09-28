package com.sologix.attendance

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sologix.attendance.sync.SyncManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SologixApplication : Application(), DefaultLifecycleObserver {

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
