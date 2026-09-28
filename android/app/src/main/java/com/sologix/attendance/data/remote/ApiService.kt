package com.sologix.attendance.data.remote

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("api/attendance/check-in")
    suspend fun checkIn(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/attendance/check-out")
    suspend fun checkOut(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/gps-points")
    suspend fun sendGpsPoint(@Body body: RequestBody): Response<ResponseBody>
}
