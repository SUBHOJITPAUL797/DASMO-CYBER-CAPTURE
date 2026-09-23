package com.example.viewmodel

import com.example.model.UserAccount

sealed class CyberAuthState {
    object Loading : CyberAuthState()
    object Unauthenticated : CyberAuthState()
    data class PendingApproval(val user: UserAccount) : CyberAuthState()
    data class DeviceMismatch(val registeredDeviceModel: String, val user: UserAccount) : CyberAuthState()
    data class Authenticated(val user: UserAccount) : CyberAuthState()
    data class Error(val message: String) : CyberAuthState()
}
