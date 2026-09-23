package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.UserAccount
import com.example.ui.theme.*
import com.example.viewmodel.CyberAuthState
import com.example.viewmodel.CyberAuthViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

@Composable
fun CyberAuthGateScreen(
    authViewModel: CyberAuthViewModel,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val authState by authViewModel.authState.collectAsState()
    val statusMsg by authViewModel.statusMessage.collectAsState()

    LaunchedEffect(Unit) {
        authViewModel.checkExistingSession(context)
    }

    LaunchedEffect(statusMsg) {
        statusMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            authViewModel.clearStatusMessage()
        }
    }

    when (val state = authState) {
        is CyberAuthState.Loading -> {
            CyberAuthLoadingView()
        }
        is CyberAuthState.Unauthenticated, is CyberAuthState.Error -> {
            CyberLoginView(
                authViewModel = authViewModel,
                errorMessage = (state as? CyberAuthState.Error)?.message
            )
        }
        is CyberAuthState.PendingApproval -> {
            CyberPendingApprovalView(
                user = state.user,
                onSignOut = { authViewModel.signOut(context) }
            )
        }
        is CyberAuthState.DeviceMismatch -> {
            CyberDeviceMismatchView(
                registeredDeviceModel = state.registeredDeviceModel,
                user = state.user,
                onSignOut = { authViewModel.signOut(context) }
            )
        }
        is CyberAuthState.Authenticated -> {
            // Unlocked and authorized! Render app content.
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                // If Super Admin, show a floating badge or access point to Admin Dashboard
                if (state.user.isAdmin) {
                    CyberAdminFloatingBadge(
                        authViewModel = authViewModel,
                        currentUser = state.user
                    )
                }
            }
        }
    }
}

@Composable
private fun CyberAuthLoadingView() {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_angle"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(CyberDarkSurface)
                    .rotate(angle),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(70.dp),
                    color = CyberCyan,
                    strokeWidth = 3.5.dp
                )
            }

            Text(
                text = "VERIFYING CLOUD LICENSE...",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = CyberCyan,
                fontSize = 14.sp,
                letterSpacing = 2.sp
            )

            Text(
                text = "Hardware Fingerprint & Security Gate",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun CyberLoginView(
    authViewModel: CyberAuthViewModel,
    errorMessage: String?
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var emailInput by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isSubmitting = false
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val email = account?.email
            if (!email.isNullOrBlank()) {
                authViewModel.loginWithEmail(context, email)
            } else {
                Toast.makeText(context, "No email returned from Google", Toast.LENGTH_SHORT).show()
            }
        } catch (e: ApiException) {
            if (e.statusCode != 12501) { // User canceled
                Toast.makeText(context, "Google Sign-In (${e.statusCode}): ${e.message}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Sign-In Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CyberDarkSurface),
            border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Cyber Icon Header
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(CyberCyan.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = CyberCyan
                    )
                }

                Text(
                    text = "DASMO CYBER CAPTURE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    color = Color.White,
                    letterSpacing = 1.5.sp
                )

                Text(
                    text = "Cloud Licensing & Hardware Security Gateway",
                    fontSize = 12.sp,
                    color = CyberCyan,
                    textAlign = TextAlign.Center
                )

                errorMessage?.let {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Red.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = it,
                            color = Color(0xFFFF6B6B),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Security Note
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Admin approval & 1-device physical hardware lock enforced in Firestore.",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                }

                // Google Sign In Button
                Button(
                    onClick = {
                        isSubmitting = true
                        try {
                            val webClientIdRes = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                            val webClientId = if (webClientIdRes != 0) context.getString(webClientIdRes) else null

                            val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()

                            if (!webClientId.isNullOrBlank()) {
                                builder.requestIdToken(webClientId)
                            }

                            val gso = builder.build()
                            val client = GoogleSignIn.getClient(context, gso)
                            googleSignInLauncher.launch(client.signInIntent)
                        } catch (e: Exception) {
                            isSubmitting = false
                            Toast.makeText(context, "Google Sign-In unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    enabled = !isSubmitting
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Continue with Google",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.3f))
                    Text(
                        text = " OR DIRECT EMAIL ",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        fontFamily = FontFamily.Monospace
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.3f))
                }

                // Email Input Field
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = { emailInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email Address", color = Color.Gray, fontSize = 12.sp) },
                    placeholder = { Text("user@example.com", color = Color.DarkGray) },
                    leadingIcon = {
                        Icon(Icons.Default.Email, contentDescription = null, tint = CyberCyan)
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboard?.hide()
                            if (emailInput.isNotBlank()) {
                                isSubmitting = true
                                authViewModel.loginWithEmail(context, emailInput)
                            }
                        }
                    ),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = CyberCyan,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
                        cursorColor = CyberCyan
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Button(
                    onClick = {
                        keyboard?.hide()
                        if (emailInput.isNotBlank()) {
                            isSubmitting = true
                            authViewModel.loginWithEmail(context, emailInput)
                        } else {
                            Toast.makeText(context, "Please enter your email", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                    enabled = !isSubmitting && emailInput.isNotBlank()
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Login, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CONNECT TO CLOUD",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CyberPendingApprovalView(
    user: UserAccount,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radar_pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CyberDarkSurface),
            border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Pending Pulsing Icon
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFB300).copy(alpha = 0.15f * alpha)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PendingActions,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = Color(0xFFFFB300)
                    )
                }

                Text(
                    text = "AWAITING APPROVAL",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = Color(0xFFFFB300),
                    letterSpacing = 1.5.sp
                )

                Text(
                    text = "Your device registration is registered in Firestore. Waiting for Super Admin approval.",
                    fontSize = 12.sp,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )

                // Live radar heartbeat
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(CyberCyan.copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Live Approval Radar Active (Zero-Click Unlock)",
                        fontSize = 11.sp,
                        color = CyberCyan,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Account & Hardware Details Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DetailRow("EMAIL", user.email)
                        DetailRow("MODEL", user.deviceModel)
                        DetailRow("DEVICE ID", user.deviceId)
                        DetailRow("APP SOURCE", user.appSource)
                        DetailRow("STATUS", "Pending Approval")
                    }
                }

                // Copy Details Button
                OutlinedButton(
                    onClick = {
                        val text = "DASMO CYBER CAPTURE ACCESS REQUEST\nEmail: ${user.email}\nDevice: ${user.deviceModel}\nDevice ID: ${user.deviceId}"
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "Registration details copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CyberCyan)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Details for Admin", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                // Sign Out Button
                Button(
                    onClick = onSignOut,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign Out / Switch Account", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CyberDeviceMismatchView(
    registeredDeviceModel: String,
    user: UserAccount,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CyberDarkSurface),
            border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color.Red.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ReportProblem,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = Color.Red
                    )
                }

                Text(
                    text = "DEVICE MISMATCH",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = Color(0xFFFF5252),
                    letterSpacing = 1.5.sp
                )

                Text(
                    text = "This account is bound to another physical device. For high security, this software is strictly restricted to one hardware device at a time.",
                    fontSize = 12.sp,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DetailRow("EMAIL", user.email)
                        DetailRow("REGISTERED DEVICE", registeredDeviceModel)
                        DetailRow("CURRENT PHONE", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        DetailRow("SOLUTION", "Ask Admin to click 'Unbind PC/Device'")
                    }
                }

                Button(
                    onClick = {
                        val text = "DASMO CYBER CAPTURE DEVICE UNBIND REQUEST\nEmail: ${user.email}\nRegistered to: $registeredDeviceModel\nNew Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "Unbind details copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Unbind Details for Admin", color = Color.White, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.Gray)
                ) {
                    Text("Sign Out", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color.Gray,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun CyberAdminFloatingBadge(
    authViewModel: CyberAuthViewModel,
    currentUser: UserAccount
) {
    var showDialog by remember { mutableStateOf(false) }
    val allUsers by authViewModel.allUsers.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        FloatingActionButton(
            onClick = { showDialog = true },
            containerColor = CyberCyan,
            contentColor = Color.Black,
            shape = CircleShape
        ) {
            Icon(Icons.Default.AdminPanelSettings, contentDescription = "Admin Panel")
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = CyberCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Cyber Capture Admin",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    Text(
                        text = "Collection: dasmo_cyber_capture_users (${allUsers.size} users)",
                        fontSize = 12.sp,
                        color = CyberCyan,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allUsers) { u ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                                border = BorderStroke(
                                    1.dp,
                                    if (u.isApproved) CyberCyan.copy(alpha = 0.5f) else Color.Red.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(text = u.email, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                    Text(text = "Device: ${u.deviceModel.ifEmpty { "None" }} (${if (u.deviceId.isNotEmpty()) "Bound" else "Unbound"})", fontSize = 10.sp, color = Color.Gray)
                                    Text(text = "Status: ${if (u.isApproved) "APPROVED" else "PENDING"}", fontSize = 10.sp, color = if (u.isApproved) Color(0xFF00E676) else Color(0xFFFF9100))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (!u.isApproved) {
                                            Button(
                                                onClick = { authViewModel.approveUser(u.email) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                                                modifier = Modifier.weight(1f).height(32.dp),
                                                contentPadding = PaddingValues(2.dp)
                                            ) {
                                                Text("Approve", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            Button(
                                                onClick = { authViewModel.revokeUser(u.email) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                                modifier = Modifier.weight(1f).height(32.dp),
                                                contentPadding = PaddingValues(2.dp)
                                            ) {
                                                Text("Revoke", fontSize = 10.sp, color = Color.White)
                                            }
                                        }

                                        if (u.deviceId.isNotEmpty()) {
                                            OutlinedButton(
                                                onClick = { authViewModel.unbindDevice(u.email) },
                                                modifier = Modifier.weight(1f).height(32.dp),
                                                contentPadding = PaddingValues(2.dp),
                                                border = BorderStroke(1.dp, CyberCyan)
                                            ) {
                                                Text("Unbind", fontSize = 10.sp, color = CyberCyan)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Close", color = CyberCyan)
                }
            },
            containerColor = CyberDarkSurface
        )
    }
}
