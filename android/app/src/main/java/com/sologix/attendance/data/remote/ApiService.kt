package com.sologix.attendance.data.remote

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {
    @POST("api/attendance/check-in")
    suspend fun checkIn(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/attendance/check-out")
    suspend fun checkOut(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/gps-points")
    suspend fun sendGpsPoint(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/attendance/history")
    suspend fun getAttendanceHistory(@Query("since") since: String? = null): Response<List<AttendanceHistoryDto>>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>
}
