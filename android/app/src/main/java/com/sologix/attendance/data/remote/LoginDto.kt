package com.sologix.attendance.data.remote

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    @SerializedName("idToken") val idToken: String
)

data class LoginResponse(
    @SerializedName("token") val token: String,
    @SerializedName("user") val user: UserDto
)

data class UserDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String?,
    @SerializedName("role") val role: String,
    @SerializedName("status") val status: String
)
