package com.sologix.attendance.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface TokenStore {
    fun getToken(): String?
    fun saveToken(token: String)
    fun clearToken()
}

class EncryptedSharedPreferencesTokenStore(context: Context) : TokenStore {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sologix_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getToken(): String? = prefs.getString("auth_token", null)

    override fun saveToken(token: String) {
        prefs.edit().putString("auth_token", token).apply()
    }

    override fun clearToken() {
        prefs.edit().remove("auth_token").apply()
    }
}
