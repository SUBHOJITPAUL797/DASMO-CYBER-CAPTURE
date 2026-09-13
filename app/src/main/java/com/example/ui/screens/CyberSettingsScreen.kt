package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack



import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CyberConfig
import com.example.model.PairedDevice
import com.example.model.StreamResolution
import com.example.ui.theme.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CyberSettingsScreen(
    config: CyberConfig,
    pairedDevices: List<PairedDevice>,
    updateInfo: com.example.updater.AppUpdateInfo = com.example.updater.AppUpdateInfo(),
    downloadState: com.example.updater.UpdateDownloadState = com.example.updater.UpdateDownloadState.Idle,
    onResolutionChanged: (StreamResolution) -> Unit,
    onToggleMirror: () -> Unit,
    onToggleGrid: () -> Unit,
    onAddDevice: (String, String) -> Unit,
    onRemoveDevice: (PairedDevice) -> Unit,
    onCheckUpdatesClick: (() -> Unit)? = null,
    onStartDownload: ((String) -> Unit)? = null,
    onInstallApk: ((java.io.File?) -> Unit)? = null,
    onCancelDownload: (() -> Unit)? = null,
    onBackClick: () -> Unit
) {
    var showAddDeviceDialog by remember { mutableStateOf(false) }
    var showUpdateModal by remember { mutableStateOf(false) }
    var newDeviceName by remember { mutableStateOf("") }
    var newDeviceIp by remember { mutableStateOf("") }
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(updateInfo.isUpdateAvailable) {
        if (updateInfo.isUpdateAvailable) {
            showUpdateModal = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = CyberTextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.testTag("btn_back_settings")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = CyberTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberDark)
            )
        },
        containerColor = CyberBlack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Stream Resolution Presets
            item {
                CyberSettingsCard(title = "Video Stream Resolution", icon = Icons.Default.Videocam) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamResolution.values().forEach { res ->
                            val isSelected = res == config.resolution
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) CyberCyan.copy(alpha = 0.12f) else CyberSurfaceVariant)
                                    .border(1.dp, if (isSelected) CyberCyan else CyberBorder.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .clickable { onResolutionChanged(res) }
                                    .padding(12.dp)
                                    .testTag("res_option_${res.name}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = res.label,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = if (isSelected) CyberCyan else CyberTextPrimary
                                    )
                                    Text(
                                        text = "${res.width}x${res.height} · ${res.desc}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = CyberTextMuted
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = CyberCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Viewfinder HUD & Mirroring
            item {
                CyberSettingsCard(title = "Viewfinder & Composition", icon = Icons.Default.Settings) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Rule of Thirds Grid", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = CyberTextPrimary)
                                Text("Display composition alignment grid overlay", fontSize = 11.sp, color = CyberTextMuted)
                            }
                            Switch(
                                checked = config.showGrid,
                                onCheckedChange = { onToggleGrid() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = CyberCyan,
                                    uncheckedThumbColor = CyberTextMuted,
                                    uncheckedTrackColor = CyberSurfaceVariant
                                ),
                                modifier = Modifier.testTag("switch_grid")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Mirror Front Camera", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = CyberTextPrimary)
                                Text("Flip front selfie feed horizontally for natural look", fontSize = 11.sp, color = CyberTextMuted)
                            }
                            Switch(
                                checked = config.isMirrored,
                                onCheckedChange = { onToggleMirror() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = CyberCyan,
                                    uncheckedThumbColor = CyberTextMuted,
                                    uncheckedTrackColor = CyberSurfaceVariant
                                ),
                                modifier = Modifier.testTag("switch_mirror")
                            )
                        }
                    }
                }
            }

            // Section 3: Paired Desktop PCs (Wi-Fi Auto-Discovery)
            item {
                CyberSettingsCard(title = "Paired Desktop Workstations", icon = Icons.Default.Computer) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (pairedDevices.isEmpty()) {
                            Text(
                                text = "No paired PCs saved. Broadcast on local network or add PC IP manually below.",
                                fontSize = 12.sp,
                                color = CyberTextMuted
                            )
                        } else {
                            pairedDevices.forEach { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CyberSurfaceVariant)
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CyberTextPrimary)
                                        Text("${device.ipAddress}:${device.port} · ${device.connectionType}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CyberTextSecondary)
                                    }

                                    IconButton(onClick = { onRemoveDevice(device) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CyberRed, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { showAddDeviceDialog = true },
                            modifier = Modifier.fillMaxWidth().testTag("btn_add_paired_device"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberSurfaceVariant, contentColor = CyberCyan)
                        ) {
                            Text("+ Add Desktop PC Manually", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Section 4: Zero USB Wireless Architecture Information
            item {
                CyberSettingsCard(title = "Desktop Driver & Audio Pipeline", icon = Icons.Default.Info) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("• Device Name in WhatsApp Desktop: \"DASMO CYBER CAPTURE\"", fontSize = 12.sp, color = CyberGreen, fontWeight = FontWeight.Medium)
                        Text("• Microphones: Direct PCM 48kHz Wireless Audio", fontSize = 12.sp, color = CyberCyan)
                        Text("• Companion Server: http://[PHONE_IP]:${config.serverPort}", fontSize = 12.sp, color = CyberTextPrimary)
                        Text("• Transmission: 100% On-The-Air Wi-Fi & LAN (Zero USB Cables Required)", fontSize = 11.sp, color = CyberTextSecondary)
                    }
                }
            }

            // Section 5: In-App Updates & Releases
            item {
                CyberSettingsCard(title = "Software Updates & Releases", icon = Icons.Default.Settings) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Current Version: v${updateInfo.currentVersion}", fontSize = 13.sp, color = CyberTextPrimary, fontWeight = FontWeight.Medium)
                                Text(if (updateInfo.isUpdateAvailable) "Latest: v${updateInfo.latestVersion} (Update Available)" else "System is up to date", fontSize = 11.sp, color = if (updateInfo.isUpdateAvailable) CyberGreen else CyberTextMuted)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (updateInfo.isUpdateAvailable) CyberGreen.copy(alpha = 0.15f) else CyberCyan.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(if (updateInfo.isUpdateAvailable) "UPDATE READY" else "UP TO DATE", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (updateInfo.isUpdateAvailable) CyberGreen else CyberCyan)
                            }
                        }

                        if (updateInfo.isUpdateAvailable) {
                            Button(
                                onClick = { showUpdateModal = true },
                                modifier = Modifier.fillMaxWidth().testTag("btn_view_update_details"),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen, contentColor = CyberBlack)
                            ) {
                                Text("View Update & Changelog (v${updateInfo.latestVersion})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }

                        Button(
                            onClick = {
                                onCheckUpdatesClick?.invoke()
                                showUpdateModal = true
                            },
                            modifier = Modifier.fillMaxWidth().testTag("btn_check_updates_settings"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.White)
                        ) {
                            Text("Check for Updates Now", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                com.example.updater.CyberUpdateManager.openUpdateLink(
                                    context,
                                    "https://github.com/SUBHOJITPAUL797/DASMO-CYBER-CAPTURE/releases"
                                )
                            },
                            modifier = Modifier.fillMaxWidth().testTag("btn_view_releases_github"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberSurfaceVariant, contentColor = CyberTextPrimary)
                        ) {
                            Text("View Releases on GitHub", fontSize = 13.sp)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showUpdateModal && (updateInfo.isUpdateAvailable || updateInfo.latestVersion.isNotEmpty())) {
        CyberUpdateModal(
            updateInfo = updateInfo,
            downloadState = downloadState,
            onStartDownload = { onStartDownload?.invoke(it) },
            onInstallApk = { onInstallApk?.invoke(it) },
            onCancelDownload = { onCancelDownload?.invoke() },
            onDismiss = { showUpdateModal = false }
        )
    }

    if (showAddDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showAddDeviceDialog = false },
            title = { Text("Add Paired Desktop PC", fontWeight = FontWeight.SemiBold, color = CyberTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newDeviceName,
                        onValueChange = { newDeviceName = it },
                        label = { Text("PC Name (e.g. Subhojit-Workstation)") },
                        modifier = Modifier.fillMaxWidth().testTag("input_device_name")
                    )
                    OutlinedTextField(
                        value = newDeviceIp,
                        onValueChange = { newDeviceIp = it },
                        label = { Text("PC IP Address (e.g. 192.168.1.100)") },
                        modifier = Modifier.fillMaxWidth().testTag("input_device_ip")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newDeviceName.isNotEmpty() && newDeviceIp.isNotEmpty()) {
                            onAddDevice(newDeviceName, newDeviceIp)
                            newDeviceName = ""
                            newDeviceIp = ""
                            showAddDeviceDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.White),
                    modifier = Modifier.testTag("btn_confirm_add_device")
                ) {
                    Text("Save Connection", fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDeviceDialog = false }) {
                    Text("Cancel", color = CyberTextSecondary)
                }
            },
            containerColor = CyberSurface
        )
    }
}

@Composable
fun CyberSettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyberBorder, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = CyberTextPrimary
                )
            }
            content()
        }
    }
}
