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

    // Tasks
    @POST("api/tasks")
    suspend fun createTask(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/tasks/history")
    suspend fun getTaskHistory(@Query("since") since: String? = null): Response<List<TaskHistoryDto>>

    // Customers
    @POST("api/customers")
    suspend fun createCustomer(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/customers/history")
    suspend fun getCustomerHistory(@Query("since") since: String? = null): Response<List<CustomerHistoryDto>>

    // Visits
    @POST("api/visits/check-in")
    suspend fun visitCheckIn(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/visits/complete")
    suspend fun visitComplete(@Body body: RequestBody): Response<ResponseBody>

    @POST("api/visits/notes")
    suspend fun visitNotes(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/visits/history")
    suspend fun getVisitHistory(@Query("since") since: String? = null): Response<List<VisitHistoryDto>>

    // Expenses
    @POST("api/expenses")
    suspend fun createExpense(@Body body: RequestBody): Response<ResponseBody>

    @GET("api/expenses/history")
    suspend fun getExpenseHistory(@Query("since") since: String? = null): Response<List<ExpenseHistoryDto>>
}
