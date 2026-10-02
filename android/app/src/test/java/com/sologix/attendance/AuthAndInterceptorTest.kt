package com.sologix.attendance

import com.sologix.attendance.data.remote.ApiService
import com.sologix.attendance.data.remote.AuthInterceptor
import com.sologix.attendance.data.remote.TokenStore
import com.sologix.attendance.data.repository.AuthRepositoryImpl
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AuthAndInterceptorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var tokenStore: TokenStore
    private lateinit var apiService: ApiService
    private lateinit var authRepository: AuthRepositoryImpl

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        tokenStore = object : TokenStore {
            private var token: String? = null
            override fun getToken(): String? = token
            override fun saveToken(token: String) { this.token = token }
            override fun clearToken() { this.token = null }
        }

        val authInterceptor = AuthInterceptor(tokenStore)

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)
        authRepository = AuthRepositoryImpl(apiService, tokenStore)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `login exchange stores app JWT and AuthInterceptor attaches it to subsequent Retrofit calls`() = runBlocking {
        // Enqueue login exchange response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "token": "jwt-app-session-token-xyz",
                        "user": {
                            "id": "user-123",
                            "name": "Jane Doe",
                            "phone": "+919999999999",
                            "role": "employee",
                            "status": "ACTIVE"
                        }
                    }
                    """.trimIndent()
                )
        )

        // Enqueue protected endpoint response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]")
        )

        // Perform login exchange
        val loginResult = authRepository.loginWithFirebaseToken("firebase-id-token-abc")
        assertTrue("Login must succeed", loginResult.isSuccess)
        assertEquals("jwt-app-session-token-xyz", tokenStore.getToken())

        // Verify request 1 (login): Firebase ID token sent in body, NOT in Authorization header
        val loginReq = mockWebServer.takeRequest()
        assertEquals("/api/auth/login", loginReq.path)
        assertNull("Login exchange must not include Authorization header", loginReq.getHeader("Authorization"))
        assertTrue(loginReq.body.readUtf8().contains("firebase-id-token-abc"))

        // Perform protected call
        val historyResponse = apiService.getAttendanceHistory()
        assertTrue(historyResponse.isSuccessful)

        // Verify request 2 (protected): Authorization header contains app JWT
        val protectedReq = mockWebServer.takeRequest()
        assertEquals("/api/attendance/history", protectedReq.path)
        assertEquals("Bearer jwt-app-session-token-xyz", protectedReq.getHeader("Authorization"))
    }

    @Test
    fun `logout clears TokenStore and removes Authorization header from subsequent calls`() = runBlocking {
        tokenStore.saveToken("pre-existing-jwt-token")
        assertEquals("pre-existing-jwt-token", tokenStore.getToken())

        // Logout
        authRepository.logout()
        assertNull("TokenStore must be cleared after logout", tokenStore.getToken())

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]")
        )

        val historyResponse = apiService.getAttendanceHistory()
        assertTrue(historyResponse.isSuccessful)

        val req = mockWebServer.takeRequest()
        assertNull("Authorization header must be null after logout", req.getHeader("Authorization"))
    }

    @Test
    fun `failed login with 403 does not store token`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Account not provisioned or inactive. Contact administrator."}""")
        )

        val result = authRepository.loginWithFirebaseToken("unrecognized-firebase-token")
        assertTrue("Login must fail on 403", result.isFailure)
        assertNull("TokenStore must not contain any token after failed login", tokenStore.getToken())
    }
}
