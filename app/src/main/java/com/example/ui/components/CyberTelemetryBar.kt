package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CyberConfig
import com.example.model.CyberStreamStats
import com.example.ui.theme.*


@Composable
fun CyberTelemetryBar(
    stats: CyberStreamStats,
    config: CyberConfig,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CyberSurface)
            .border(1.dp, CyberBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("cyber_telemetry_bar"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Row 1: Network & Transmit Status Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = "Wi-Fi LAN",
                    tint = if (stats.isStreaming) StudioPrimary else StudioTextSecondary,
                    modifier = Modifier.size(16.dp)
                )

                Text(
                    text = "${stats.wifiSsid} · ${stats.serverIp}:${stats.serverPort}",
                    color = StudioTextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium
                )
            }

            // Studio Status Badge (Live / Standby)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (stats.isStreaming) StudioSuccessDim else StudioSurfaceElevated)
                    .border(
                        1.dp,
                        if (stats.isStreaming) StudioSuccess.copy(alpha = 0.5f) else StudioBorderSubtle,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .scale(if (stats.isStreaming) pulseScale else 1f)
                        .clip(CircleShape)
                        .background(if (stats.isStreaming) StudioSuccess else StudioTextMuted)
                )

                Text(
                    text = if (stats.isStreaming) "LIVE" else "Standby",
                    color = if (stats.isStreaming) StudioSuccess else StudioTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Row 2: Metrics (FPS, Bitrate, Clients, Battery, Audio VU)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed & FPS
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = "FPS",
                    tint = StudioPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "${stats.fps} FPS · ${stats.bitrateKbps}k",
                    color = StudioTextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }

            // Connected Clients
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Computer,
                    contentDescription = "Connected PCs",
                    tint = if (stats.connectedClients > 0) StudioSuccess else StudioTextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (stats.connectedClients == 1) "1 PC" else "${stats.connectedClients} PCs",
                    color = if (stats.connectedClients > 0) StudioSuccess else StudioTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium
                )
            }

            // Audio VU Level Meter
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = if (config.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Microphone",
                    tint = if (config.isMicMuted) StudioDanger else StudioTextSecondary,
                    modifier = Modifier.size(14.dp)
                )

                // Simple Mini VU Level Bar
                val normalizedVu = if (config.isMicMuted) 0f else ((stats.micLevelDb + 60f) / 60f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(StudioSurfaceElevated)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(normalizedVu)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (normalizedVu > 0.85f) StudioDanger else StudioSuccess)
                    )
                }
            }

            // Battery & Temp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = "Battery",
                    tint = if (stats.batteryPercent < 20) StudioDanger else StudioTextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "${stats.batteryPercent}%",
                    color = StudioTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Row 3: AirLink Companion & Headphone / Audio Device Status Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(StudioDarkBg)
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .testTag("banner_airlink_status"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: AirLink Connection Status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (stats.connectedClients > 0) StudioSuccess else StudioTextMuted)
                )
                Text(
                    text = if (stats.connectedClients > 0) "PC Companion Connected" else "AirLink Ready · Standby",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    color = if (stats.connectedClients > 0) StudioSuccess else StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right: Connected Audio Output Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (stats.hasHeadphones) StudioSuccessDim else StudioSurfaceElevated)
                    .border(
                        1.dp,
                        if (stats.hasHeadphones) StudioSuccess.copy(alpha = 0.35f) else StudioBorderSubtle,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = stats.audioOutputDevice,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    color = if (stats.hasHeadphones) StudioSuccess else StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp)
                )
            }
        }
    }
}
