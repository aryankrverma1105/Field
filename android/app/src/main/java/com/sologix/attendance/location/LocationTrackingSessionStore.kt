package com.sologix.attendance.location

import android.content.Context
import android.content.SharedPreferences

class LocationTrackingSessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sologix_tracking_session", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_IS_ACTIVE = "is_active"
        private const val KEY_USER_ID = "user_id"
    }

    fun saveSession(userId: String) {
        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, true)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, false)
            .remove(KEY_USER_ID)
            .apply()
    }

    fun isActive(): Boolean = prefs.getBoolean(KEY_IS_ACTIVE, false)

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
}
