package com.example.model

data class UserAccount(
    val email: String = "",
    val deviceId: String = "",
    val deviceModel: String = "",
    val isApproved: Boolean = false,
    val isAdmin: Boolean = false,
    val role: String = "user", // "admin" or "user"
    val status: String = "pending", // "pending", "approved", "rejected"
    val currentSessionToken: String = "",
    val expiryTimestamp: Long = 0L, // 0L means lifetime access, otherwise expiration timestamp in ms
    val registrationTimestamp: Long = 0L,
    val lastActiveTimestamp: Long = 0L,
    val appSource: String = "DASMO CYBER CAPTURE"
)
