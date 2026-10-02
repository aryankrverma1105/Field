package com.sologix.attendance.data.repository

import com.sologix.attendance.data.remote.ApiService
import com.sologix.attendance.data.remote.LoginRequest
import com.sologix.attendance.data.remote.LoginResponse
import com.sologix.attendance.data.remote.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

interface AuthRepository {
    fun getStoredToken(): String?
    fun logout()
    suspend fun loginWithFirebaseToken(idToken: String): Result<LoginResponse>
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val tokenStore: TokenStore
) : AuthRepository {

    override fun getStoredToken(): String? = tokenStore.getToken()

    override fun logout() {
        tokenStore.clearToken()
    }

    override suspend fun loginWithFirebaseToken(idToken: String): Result<LoginResponse> = runCatching {
        val response = apiService.login(LoginRequest(idToken = idToken))
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string()
            throw IllegalStateException(errorBody ?: "Login failed with HTTP ${response.code()}")
        }
        val body = response.body() ?: throw IllegalStateException("Empty response from login endpoint")

        // Store application JWT token; Firebase ID token is discarded immediately
        tokenStore.saveToken(body.token)
        body
    }
}
