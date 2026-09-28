package com.sologix.attendance.location

import android.location.Location
import android.os.Build

/**
 * Fix 4: Mock Location Detector with explicit SDK version guard.
 *
 * Decisions:
 * 1. minSdkVersion = 26 (Android 8.0 Oreo)
 * 2. On Android 12 (API 31, Build.VERSION_CODES.S) and above: uses location.isMock
 * 3. On Android 8.0 to Android 11 (API 26-30): safely falls back to location.isFromMockProvider
 *    preventing NoSuchMethodError crashes at runtime on older devices.
 */
class MockLocationDetector(
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
) {

    fun isMock(location: Location): Boolean {
        val currentSdk = sdkIntProvider()
        return if (currentSdk >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }
}
