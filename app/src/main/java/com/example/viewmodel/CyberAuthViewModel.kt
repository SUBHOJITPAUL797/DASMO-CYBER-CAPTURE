package com.example.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.UserAccount
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class CyberAuthViewModel : ViewModel() {

    companion object {
        const val SUPER_ADMIN_EMAIL = "subhojitpaul26042004@gmail.com"
        const val COLLECTION_NAME = "dasmo_cyber_capture_users"
        private const val PREFS_NAME = "dasmo_cyber_capture_auth"
        private const val KEY_EMAIL = "saved_auth_email"
        private const val KEY_SESSION = "saved_session_token"
    }

    private val db = FirebaseFirestore.getInstance()

    private val _authState = MutableStateFlow<CyberAuthState>(CyberAuthState.Loading)
    val authState: StateFlow<CyberAuthState> = _authState.asStateFlow()

    private val _currentUser = MutableStateFlow<UserAccount?>(null)
    val currentUser: StateFlow<UserAccount?> = _currentUser.asStateFlow()

    private val _allUsers = MutableStateFlow<List<UserAccount>>(emptyList())
    val allUsers: StateFlow<List<UserAccount>> = _allUsers.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var userDocListener: ListenerRegistration? = null
    private var allUsersListener: ListenerRegistration? = null

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown_android_device"
    }

    fun getDeviceModel(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun checkExistingSession(context: Context) {
        val prefs = getPrefs(context)
        val savedEmail = prefs.getString(KEY_EMAIL, null)
        val savedSession = prefs.getString(KEY_SESSION, null)

        if (savedEmail.isNullOrBlank()) {
            _authState.value = CyberAuthState.Unauthenticated
        } else {
            loginWithEmail(context, savedEmail, savedSession)
        }
    }

    fun loginWithEmail(
        context: Context,
        rawEmail: String,
        existingSessionToken: String? = null
    ) {
        val email = rawEmail.trim().lowercase()
        if (email.isBlank() || !email.contains("@")) {
            _statusMessage.value = "Please enter a valid email address."
            _authState.value = CyberAuthState.Unauthenticated
            return
        }

        _authState.value = CyberAuthState.Loading
        val deviceId = getDeviceId(context)
        val deviceModel = getDeviceModel()
        val isSuperAdmin = (email == SUPER_ADMIN_EMAIL)
        val sessionToken = existingSessionToken ?: UUID.randomUUID().toString()

        // Save locally
        getPrefs(context).edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_SESSION, sessionToken)
            .apply()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val docRef = db.collection(COLLECTION_NAME).document(email)
                val snapshot = docRef.get().await()

                if (snapshot != null && snapshot.exists()) {
                    val rawApproved = snapshot.getBoolean("isApproved") ?: false
                    val rawStatus = snapshot.getString("status") ?: "pending"
                    val rawRole = snapshot.getString("role") ?: if (isSuperAdmin) "admin" else "user"
                    val rawDeviceId = snapshot.getString("deviceId") ?: ""
                    val rawDeviceModel = snapshot.getString("deviceModel") ?: ""
                    val rawExpiry = snapshot.getLong("expiryTimestamp") ?: 0L
                    val rawAdmin = isSuperAdmin || rawRole == "admin" || (snapshot.getBoolean("isAdmin") ?: false)
                    val rawRegTime = snapshot.getLong("registrationTimestamp") ?: System.currentTimeMillis()

                    val isUserApproved = rawApproved || rawStatus == "approved" || rawAdmin

                    val account = UserAccount(
                        email = email,
                        deviceId = rawDeviceId,
                        deviceModel = rawDeviceModel,
                        isApproved = isUserApproved,
                        isAdmin = rawAdmin,
                        role = if (rawAdmin) "admin" else rawRole,
                        status = if (rawAdmin) "approved" else rawStatus,
                        currentSessionToken = sessionToken,
                        expiryTimestamp = rawExpiry,
                        registrationTimestamp = rawRegTime,
                        lastActiveTimestamp = System.currentTimeMillis(),
                        appSource = "DASMO CYBER CAPTURE"
                    )

                    withContext(Dispatchers.Main) {
                        _currentUser.value = account

                        // 1. Strict Hardware Device Check
                        if (rawDeviceId.isNotEmpty() && rawDeviceId != deviceId) {
                            _authState.value = CyberAuthState.DeviceMismatch(
                                registeredDeviceModel = rawDeviceModel.ifEmpty { "Another Device" },
                                user = account
                            )
                            startUserDocListener(email, deviceId)
                            return@withContext
                        }

                        // 2. Bind hardware if empty
                        if (rawDeviceId.isEmpty()) {
                            docRef.update(
                                mapOf(
                                    "deviceId" to deviceId,
                                    "deviceModel" to deviceModel,
                                    "lastActiveTimestamp" to System.currentTimeMillis()
                                )
                            )
                        } else {
                            docRef.update("lastActiveTimestamp", System.currentTimeMillis())
                        }

                        // 3. Admin or Approved
                        if (rawAdmin) {
                            _authState.value = CyberAuthState.Authenticated(account)
                            startUserDocListener(email, deviceId)
                            startAllUsersListener()
                            return@withContext
                        }

                        // 4. Check Expiration
                        val isExpired = rawExpiry > 0L && System.currentTimeMillis() > rawExpiry
                        if (isUserApproved && !isExpired) {
                            _authState.value = CyberAuthState.Authenticated(account)
                        } else {
                            _authState.value = CyberAuthState.PendingApproval(account)
                        }

                        startUserDocListener(email, deviceId)
                    }
                } else {
                    // Create new registration record in Firestore
                    val newAccount = UserAccount(
                        email = email,
                        deviceId = deviceId,
                        deviceModel = deviceModel,
                        isApproved = isSuperAdmin,
                        isAdmin = isSuperAdmin,
                        role = if (isSuperAdmin) "admin" else "user",
                        status = if (isSuperAdmin) "approved" else "pending",
                        currentSessionToken = sessionToken,
                        expiryTimestamp = 0L,
                        registrationTimestamp = System.currentTimeMillis(),
                        lastActiveTimestamp = System.currentTimeMillis(),
                        appSource = "DASMO CYBER CAPTURE"
                    )

                    val docData = hashMapOf(
                        "email" to email,
                        "deviceId" to deviceId,
                        "deviceModel" to deviceModel,
                        "isApproved" to isSuperAdmin,
                        "isAdmin" to isSuperAdmin,
                        "role" to if (isSuperAdmin) "admin" else "user",
                        "status" to if (isSuperAdmin) "approved" else "pending",
                        "currentSessionToken" to sessionToken,
                        "expiryTimestamp" to 0L,
                        "registrationTimestamp" to System.currentTimeMillis(),
                        "lastActiveTimestamp" to System.currentTimeMillis(),
                        "appSource" to "DASMO CYBER CAPTURE"
                    )

                    docRef.set(docData, SetOptions.merge()).await()

                    withContext(Dispatchers.Main) {
                        _currentUser.value = newAccount
                        if (isSuperAdmin) {
                            _authState.value = CyberAuthState.Authenticated(newAccount)
                            startAllUsersListener()
                        } else {
                            _authState.value = CyberAuthState.PendingApproval(newAccount)
                        }
                        startUserDocListener(email, deviceId)
                    }
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Login error: ${ex.localizedMessage ?: "Unknown error"}"
                    _authState.value = CyberAuthState.Error(ex.localizedMessage ?: "Failed to connect to authentication server")
                }
            }
        }
    }

    private fun startUserDocListener(email: String, currentDeviceId: String) {
        userDocListener?.remove()
        val docRef = db.collection(COLLECTION_NAME).document(email)

        userDocListener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val rawApproved = snapshot.getBoolean("isApproved") ?: false
                val rawStatus = snapshot.getString("status") ?: "pending"
                val rawRole = snapshot.getString("role") ?: "user"
                val rawDeviceId = snapshot.getString("deviceId") ?: ""
                val rawDeviceModel = snapshot.getString("deviceModel") ?: ""
                val rawExpiry = snapshot.getLong("expiryTimestamp") ?: 0L
                val isSuperAdmin = (email == SUPER_ADMIN_EMAIL)
                val rawAdmin = isSuperAdmin || rawRole == "admin" || (snapshot.getBoolean("isAdmin") ?: false)
                val rawRegTime = snapshot.getLong("registrationTimestamp") ?: System.currentTimeMillis()

                val isApproved = rawApproved || rawStatus == "approved" || rawAdmin
                val isExpired = rawExpiry > 0L && System.currentTimeMillis() > rawExpiry

                val updatedUser = UserAccount(
                    email = email,
                    deviceId = rawDeviceId,
                    deviceModel = rawDeviceModel,
                    isApproved = isApproved,
                    isAdmin = rawAdmin,
                    role = if (rawAdmin) "admin" else rawRole,
                    status = if (rawAdmin) "approved" else rawStatus,
                    currentSessionToken = _currentUser.value?.currentSessionToken ?: "",
                    expiryTimestamp = rawExpiry,
                    registrationTimestamp = rawRegTime,
                    lastActiveTimestamp = System.currentTimeMillis(),
                    appSource = "DASMO CYBER CAPTURE"
                )
                _currentUser.value = updatedUser

                // Check device mismatch
                if (rawDeviceId.isNotEmpty() && rawDeviceId != currentDeviceId) {
                    _authState.value = CyberAuthState.DeviceMismatch(
                        registeredDeviceModel = rawDeviceModel.ifEmpty { "Another Device" },
                        user = updatedUser
                    )
                    return@addSnapshotListener
                }

                if (rawAdmin) {
                    _authState.value = CyberAuthState.Authenticated(updatedUser)
                    return@addSnapshotListener
                }

                if (isApproved && !isExpired) {
                    _authState.value = CyberAuthState.Authenticated(updatedUser)
                } else {
                    _authState.value = CyberAuthState.PendingApproval(updatedUser)
                }
            } else {
                _authState.value = CyberAuthState.Unauthenticated
            }
        }
    }

    private fun startAllUsersListener() {
        allUsersListener?.remove()
        allUsersListener = db.collection(COLLECTION_NAME).addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            if (snapshot != null) {
                val list = mutableListOf<UserAccount>()
                for (doc in snapshot.documents) {
                    val email = doc.getString("email") ?: doc.id
                    val isSuperAdmin = (email == SUPER_ADMIN_EMAIL)
                    val rawRole = doc.getString("role") ?: if (isSuperAdmin) "admin" else "user"
                    val rawAdmin = isSuperAdmin || rawRole == "admin" || (doc.getBoolean("isAdmin") ?: false)
                    val rawApproved = doc.getBoolean("isApproved") ?: false
                    val rawStatus = doc.getString("status") ?: "pending"

                    list.add(
                        UserAccount(
                            email = email,
                            deviceId = doc.getString("deviceId") ?: "",
                            deviceModel = doc.getString("deviceModel") ?: "",
                            isApproved = rawApproved || rawStatus == "approved" || rawAdmin,
                            isAdmin = rawAdmin,
                            role = if (rawAdmin) "admin" else rawRole,
                            status = if (rawAdmin) "approved" else rawStatus,
                            currentSessionToken = doc.getString("currentSessionToken") ?: "",
                            expiryTimestamp = doc.getLong("expiryTimestamp") ?: 0L,
                            registrationTimestamp = doc.getLong("registrationTimestamp") ?: 0L,
                            lastActiveTimestamp = doc.getLong("lastActiveTimestamp") ?: 0L,
                            appSource = doc.getString("appSource") ?: "DASMO CYBER CAPTURE"
                        )
                    )
                }
                _allUsers.value = list
            }
        }
    }

    // Admin Operations
    fun approveUser(targetEmail: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.collection(COLLECTION_NAME).document(targetEmail.trim().lowercase()).update(
                    mapOf(
                        "isApproved" to true,
                        "status" to "approved"
                    )
                ).await()
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Approved $targetEmail"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Failed: ${e.message}"
                }
            }
        }
    }

    fun revokeUser(targetEmail: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.collection(COLLECTION_NAME).document(targetEmail.trim().lowercase()).update(
                    mapOf(
                        "isApproved" to false,
                        "status" to "pending"
                    )
                ).await()
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Access revoked for $targetEmail"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Failed: ${e.message}"
                }
            }
        }
    }

    fun unbindDevice(targetEmail: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.collection(COLLECTION_NAME).document(targetEmail.trim().lowercase()).update(
                    mapOf(
                        "deviceId" to "",
                        "deviceModel" to ""
                    )
                ).await()
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Unbound hardware for $targetEmail"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Failed: ${e.message}"
                }
            }
        }
    }

    fun setLifetimeLicense(targetEmail: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.collection(COLLECTION_NAME).document(targetEmail.trim().lowercase()).update(
                    mapOf(
                        "isApproved" to true,
                        "status" to "approved",
                        "expiryTimestamp" to 0L
                    )
                ).await()
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Lifetime license set for $targetEmail"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Failed: ${e.message}"
                }
            }
        }
    }

    fun signOut(context: Context) {
        userDocListener?.remove()
        userDocListener = null
        allUsersListener?.remove()
        allUsersListener = null
        _currentUser.value = null
        _allUsers.value = emptyList()

        getPrefs(context).edit().clear().apply()
        _authState.value = CyberAuthState.Unauthenticated
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        userDocListener?.remove()
        allUsersListener?.remove()
    }
}
