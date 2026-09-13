package com.example.ui.screens

import android.Manifest
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.example.ui.components.CyberControlDock
import com.example.ui.components.CyberTelemetryBar
import com.example.ui.components.CyberViewfinder
import com.example.ui.theme.*
import com.example.viewmodel.CyberCaptureViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CyberDashboardScreen(
    viewModel: CyberCaptureViewModel,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val isStreaming by viewModel.isStreamingActive.collectAsState()
    val qrBitmap by viewModel.qrCodeBitmap.collectAsState()
    val toastMsg by viewModel.toastMessage.collectAsState()
    val updateInfo by viewModel.appUpdateInfo.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()

    var showWebPortalModal by remember { mutableStateOf(false) }
    var showFiltersModal by remember { mutableStateOf(false) }
    var showAudioModal by remember { mutableStateOf(false) }
    var showUpdateModal by remember { mutableStateOf(false) }

    LaunchedEffect(updateInfo.isUpdateAvailable) {
        if (updateInfo.isUpdateAvailable) {
            showUpdateModal = true
        }
    }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        containerColor = CyberBlack
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp)
                .testTag("cyber_dashboard_screen")
        ) {
            if (!permissionsState.allPermissionsGranted) {
                // Permission Request Screen
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CyberSurface)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "DASMO Studio Capture",
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = StudioTextPrimary
                        )
                        Text(
                            text = "To stream high-definition video and audio wirelessly over Wi-Fi to your PC companion, camera and microphone permissions are required.",
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Default,
                            color = StudioTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = { permissionsState.launchMultiplePermissionRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = StudioPrimary, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("btn_grant_permissions")
                        ) {
                            Text("Grant Hardware Access", fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Top Telemetry Strip (Integrated with AirLink & Audio Device Status)
                    CyberTelemetryBar(
                        stats = stats,
                        config = config
                    )

                    // In-App OTA Update Prompt Banner
                    AnimatedVisibility(
                        visible = updateInfo.isUpdateAvailable,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Surface(
                            onClick = { showUpdateModal = true },
                            shape = RoundedCornerShape(12.dp),
                            color = StudioPrimaryDim,
                            border = BorderStroke(1.dp, StudioPrimary.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().testTag("banner_update_available")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SystemUpdate,
                                        contentDescription = null,
                                        tint = StudioPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Update Available · v${updateInfo.latestVersion}",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Default,
                                        fontWeight = FontWeight.SemiBold,
                                        color = StudioTextPrimary
                                    )
                                }
                                Text(
                                    text = "Update →",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Default,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StudioPrimary
                                )
                            }
                        }
                    }

                    // Main Viewfinder - Expands to fill available vertical space
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        CyberViewfinder(
                            viewModel = viewModel,
                            config = config,
                            stats = stats,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Bottom Floating Control Dock
                    CyberControlDock(
                        config = config,
                        isStreaming = isStreaming,
                        onStartStopClick = {
                            if (isStreaming) {
                                viewModel.stopCapture()
                            } else {
                                viewModel.startCapture()
                            }
                        },
                        onSwitchCameraClick = { viewModel.switchCamera() },
                        onToggleTorchClick = { viewModel.toggleTorch() },
                        onTogglePauseVideoClick = { viewModel.toggleVideoPause() },
                        onToggleMicClick = { viewModel.toggleMic() },
                        onToggleSpeakerClick = { viewModel.toggleSpeakerOutput() },
                        onOpenFiltersClick = { showFiltersModal = true },
                        onOpenAudioStudioClick = { showAudioModal = true },
                        onOpenQrPortalClick = { showWebPortalModal = true },
                        onOpenSettingsClick = onNavigateToSettings
                    )
                }
            }

            // Modals
            if (showWebPortalModal) {
                CyberWebPortalModal(
                    stats = stats,
                    qrBitmap = qrBitmap,
                    onDismiss = { showWebPortalModal = false }
                )
            }

            if (showFiltersModal) {
                CyberFiltersDrawer(
                    activeFilter = config.activeFilter,
                    onFilterSelected = { filter ->
                        viewModel.setFilter(filter)
                        showFiltersModal = false
                    },
                    onDismiss = { showFiltersModal = false }
                )
            }

            if (showAudioModal) {
                CyberAudioStudioModal(
                    config = config,
                    stats = stats,
                    onMicGainChanged = { viewModel.setMicGain(it) },
                    onSpeakerVolumeChanged = { viewModel.setSpeakerVolume(it) },
                    onAudioRoutingChanged = { viewModel.setAudioRouting(it) },
                    onDismiss = { showAudioModal = false }
                )
            }

            if (showUpdateModal && updateInfo.isUpdateAvailable) {
                CyberUpdateModal(
                    updateInfo = updateInfo,
                    downloadState = downloadState,
                    onStartDownload = { viewModel.startInAppDownload(it) },
                    onInstallApk = { viewModel.installDownloadedApk(it) },
                    onCancelDownload = { viewModel.cancelInAppDownload() },
                    onDismiss = { showUpdateModal = false }
                )
            }
        }
    }
}
