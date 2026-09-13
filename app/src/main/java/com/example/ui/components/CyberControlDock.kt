package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CyberConfig
import com.example.ui.theme.*


@Composable
fun CyberControlDock(
    config: CyberConfig,
    isStreaming: Boolean,
    onStartStopClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    onToggleTorchClick: () -> Unit,
    onTogglePauseVideoClick: () -> Unit,
    onToggleMicClick: () -> Unit,
    onToggleSpeakerClick: () -> Unit,
    onOpenFiltersClick: () -> Unit,
    onOpenAudioStudioClick: () -> Unit,
    onOpenQrPortalClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val startButtonBg by animateColorAsState(
        targetValue = if (isStreaming) StudioDanger else StudioPrimary,
        animationSpec = tween(300),
        label = "btn_bg"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(StudioSurface)
            .border(1.dp, StudioBorder, RoundedCornerShape(20.dp))
            .padding(14.dp)
            .testTag("cyber_control_dock"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Quick Action Icon Row (Simultaneous Call Controls)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CyberDockIconButton(
                icon = if (config.isVideoPaused) Icons.Default.Pause else Icons.Default.Videocam,
                label = if (config.isVideoPaused) "Paused" else "Video",
                isActive = config.isVideoPaused,
                activeColor = StudioDanger,
                inactiveColor = StudioTextSecondary,
                testTag = "btn_pause_video",
                onClick = onTogglePauseVideoClick
            )

            CyberDockIconButton(
                icon = if (config.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (config.isMicMuted) "Muted" else "Mic On",
                isActive = !config.isMicMuted,
                activeColor = StudioSuccess,
                inactiveColor = StudioDanger,
                testTag = "btn_toggle_mic",
                onClick = onToggleMicClick
            )

            CyberDockIconButton(
                icon = if (config.isSpeakerEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                label = if (config.isSpeakerEnabled) "Speaker" else "Spk Off",
                isActive = config.isSpeakerEnabled,
                activeColor = StudioPrimary,
                inactiveColor = StudioTextSecondary,
                testTag = "btn_toggle_speaker",
                onClick = onToggleSpeakerClick
            )

            CyberDockIconButton(
                icon = Icons.Default.Cameraswitch,
                label = "Flip",
                testTag = "btn_flip_camera",
                onClick = onSwitchCameraClick
            )

            CyberDockIconButton(
                icon = if (config.isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                label = "Torch",
                isActive = config.isTorchOn,
                activeColor = StudioWarning,
                testTag = "btn_toggle_torch",
                onClick = onToggleTorchClick
            )

            CyberDockIconButton(
                icon = Icons.Default.GraphicEq,
                label = "Audio",
                testTag = "btn_open_audio_studio",
                onClick = onOpenAudioStudioClick
            )

            CyberDockIconButton(
                icon = Icons.Default.QrCode,
                label = "PC Link",
                testTag = "btn_open_pc_link",
                onClick = onOpenQrPortalClick
            )

            CyberDockIconButton(
                icon = Icons.Default.Settings,
                label = "Settings",
                testTag = "btn_open_settings",
                onClick = onOpenSettingsClick
            )
        }

        // Master TRANSMIT / STOP Stream Button
        Button(
            onClick = onStartStopClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .shadow(
                    elevation = if (isStreaming) 8.dp else 4.dp,
                    shape = RoundedCornerShape(12.dp),
                    spotColor = startButtonBg.copy(alpha = 0.5f)
                )
                .testTag("btn_master_start_stop"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = startButtonBg,
                contentColor = Color.White
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isStreaming) "Stop Streaming" else "Start Camera Stream",
                    modifier = Modifier.size(20.dp)
                )

                Text(
                    text = if (isStreaming) "Stop Streaming" else "Start Camera Stream",
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}

@Composable
fun CyberDockIconButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    activeColor: Color = StudioPrimary,
    inactiveColor: Color = StudioTextSecondary,
    testTag: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isActive) activeColor.copy(alpha = 0.16f) else StudioSurfaceElevated)
                .border(
                    1.dp,
                    if (isActive) activeColor.copy(alpha = 0.7f) else StudioBorderSubtle,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else inactiveColor,
                modifier = Modifier.size(18.dp)
            )
        }

        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Default,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isActive) activeColor else StudioTextSecondary
        )
    }
}
