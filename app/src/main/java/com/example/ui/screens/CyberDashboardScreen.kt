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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.ui.components.CyberControlDock
import com.example.ui.components.CyberTelemetryBar
import com.example.ui.components.CyberViewfinder
import com.example.ui.theme.CyberBlack
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
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

    var showWebPortalModal by remember { mutableStateOf(false) }
    var showFiltersModal by remember { mutableStateOf(false) }
    var showAudioModal by remember { mutableStateOf(false) }

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
                            text = "DASMO CYBER CAPTURE",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = CyberCyan
                        )
                        Text(
                            text = "To stream your camera & microphone wirelessly over Wi-Fi to your PC, grant Camera and Microphone access.",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyberTextSecondary
                        )
                        Button(
                            onClick = { permissionsState.launchMultiplePermissionRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberBlack),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("btn_grant_permissions")
                        ) {
                            Text("Grant Hardware Access", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Top Telemetry Strip
                    CyberTelemetryBar(
                        stats = stats,
                        config = config
                    )

                    // Main Viewfinder
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        CyberViewfinder(
                            viewModel = viewModel,
                            config = config,
                            stats = stats
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
        }
    }
}
