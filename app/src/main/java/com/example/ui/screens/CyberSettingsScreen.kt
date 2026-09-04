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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.example.ui.theme.CyberBlack
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberDark
import com.example.ui.theme.CyberGreen
import com.example.ui.theme.CyberRed
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CyberSettingsScreen(
    config: CyberConfig,
    pairedDevices: List<PairedDevice>,
    updateInfo: com.example.updater.AppUpdateInfo = com.example.updater.AppUpdateInfo(),
    onResolutionChanged: (StreamResolution) -> Unit,
    onToggleMirror: () -> Unit,
    onToggleGrid: () -> Unit,
    onAddDevice: (String, String) -> Unit,
    onRemoveDevice: (PairedDevice) -> Unit,
    onCheckUpdatesClick: (() -> Unit)? = null,
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
                        text = "SETTINGS // CONFIGURATION",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = CyberCyan
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.testTag("btn_back_settings")) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = CyberCyan
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
                CyberSettingsCard(title = "VIDEO STREAM RESOLUTION", icon = Icons.Default.Videocam) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamResolution.values().forEach { res ->
                            val isSelected = res == config.resolution
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) CyberCyan.copy(alpha = 0.15f) else CyberSurfaceVariant)
                                    .border(1.dp, if (isSelected) CyberCyan else CyberBorder, RoundedCornerShape(8.dp))
                                    .clickable { onResolutionChanged(res) }
                                    .padding(12.dp)
                                    .testTag("res_option_${res.name}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = res.label,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (isSelected) CyberCyan else CyberTextPrimary
                                    )
                                    Text(
                                        text = "${res.width}x${res.height} · ${res.desc}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
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
                CyberSettingsCard(title = "VIEWFINDER & HUD OPTIONS", icon = Icons.Default.Settings) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Rule of Thirds Grid", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = CyberTextPrimary)
                                Text("Display alignment composition overlay", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberTextMuted)
                            }
                            Switch(
                                checked = config.showGrid,
                                onCheckedChange = { onToggleGrid() },
                                colors = SwitchDefaults.colors(checkedThumbColor = CyberCyan, checkedTrackColor = CyberCyan.copy(alpha = 0.4f)),
                                modifier = Modifier.testTag("switch_grid")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Mirror Front Camera", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = CyberTextPrimary)
                                Text("Flip selfie feed horizontally for natural mirror look", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberTextMuted)
                            }
                            Switch(
                                checked = config.isMirrored,
                                onCheckedChange = { onToggleMirror() },
                                colors = SwitchDefaults.colors(checkedThumbColor = CyberCyan, checkedTrackColor = CyberCyan.copy(alpha = 0.4f)),
                                modifier = Modifier.testTag("switch_mirror")
                            )
                        }
                    }
                }
            }

            // Section 3: Paired Desktop PCs (Wi-Fi Auto-Discovery)
            item {
                CyberSettingsCard(title = "PAIRED DESKTOP PCs (WI-FI / LAN)", icon = Icons.Default.Computer) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (pairedDevices.isEmpty()) {
                            Text(
                                text = "No paired PCs saved. Broadcast on local network or add PC IP manually below.",
                                fontSize = 11.sp,
                                color = CyberTextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            pairedDevices.forEach { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CyberSurfaceVariant)
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(device.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = CyberTextPrimary)
                                        Text("${device.ipAddress}:${device.port} · ${device.connectionType}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberTextSecondary)
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
                            Text("+ Add Desktop PC Manually", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Section 4: Zero USB Wireless Architecture Information
            item {
                CyberSettingsCard(title = "NATIVE DESKTOP DRIVER & ZERO-USB SPEC", icon = Icons.Default.Info) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("• Device Name in WhatsApp Desktop: \"DASMO CYBER CAPTURE\"", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = CyberGreen)
                        Text("• Microphones: \"DASMO Cyber Microphone\" (Direct PCM 48kHz)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CyberCyan)
                        Text("• 1-Click Desktop Installer: Download directly from http://[PHONE_IP]:${config.serverPort}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CyberTextPrimary)
                        Text("• Protocol: HTTP Multipart MJPEG + Raw PCM Full-Duplex Audio", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = CyberTextSecondary)
                        Text("• Transmission: 100% On-The-Air Wi-Fi & LAN (Zero USB Cables Required)", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = CyberCyan)
                    }
                }
            }

            // Section 5: In-App Updates & Releases
            item {
                CyberSettingsCard(title = "CYBER OTA UPDATE & RELEASES", icon = Icons.Default.Settings) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Current Version: v${updateInfo.currentVersion}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = CyberTextPrimary, fontWeight = FontWeight.Bold)
                                Text(if (updateInfo.isUpdateAvailable) "Latest: v${updateInfo.latestVersion} (Update Available)" else "System is up to date", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (updateInfo.isUpdateAvailable) CyberGreen else CyberTextMuted)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (updateInfo.isUpdateAvailable) CyberGreen.copy(alpha = 0.2f) else CyberCyan.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(if (updateInfo.isUpdateAvailable) "UPDATE" else "LATEST", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = if (updateInfo.isUpdateAvailable) CyberGreen else CyberCyan, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (updateInfo.isUpdateAvailable) {
                            Button(
                                onClick = { showUpdateModal = true },
                                modifier = Modifier.fillMaxWidth().testTag("btn_view_update_details"),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen, contentColor = CyberBlack)
                            ) {
                                Text("🚀 View Update & Changelog (v${updateInfo.latestVersion})", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        Button(
                            onClick = {
                                onCheckUpdatesClick?.invoke()
                                if (updateInfo.isUpdateAvailable) {
                                    showUpdateModal = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("btn_check_updates_settings"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberBlack)
                        ) {
                            Text("🔄 Check for Updates Now", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                            Text("🌐 View Releases on GitHub", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showUpdateModal && updateInfo.isUpdateAvailable) {
        CyberUpdateModal(
            updateInfo = updateInfo,
            onDismiss = { showUpdateModal = false }
        )
    }

    if (showAddDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showAddDeviceDialog = false },
            title = { Text("Add Paired Desktop PC", fontFamily = FontFamily.Monospace, color = CyberCyan) },
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
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberBlack),
                    modifier = Modifier.testTag("btn_confirm_add_device")
                ) {
                    Text("Save Pair", fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDeviceDialog = false }) {
                    Text("Cancel", fontFamily = FontFamily.Monospace, color = CyberTextSecondary)
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyberBorder, RoundedCornerShape(16.dp))
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
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = CyberCyan
                )
            }
            content()
        }
    }
}
