package com.sologix.attendance.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sologix.attendance.sync.SyncManager

/**
 * Phase 1 Item 7 & Verification Item 6:
 * Listens for device reboot to resume sync and re-enqueue periodic backstop.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            // 1. Perform crash recovery for any interrupted syncs
            SyncManager.performCrashRecovery(context)

            // 2. Re-enqueue 15-minute periodic backstop
            SyncManager.schedulePeriodicSync(context)
        }
    }
}
