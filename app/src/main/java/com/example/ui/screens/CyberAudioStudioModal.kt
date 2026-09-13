package com.example.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.VolumeUp



import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.AudioRouting
import com.example.model.CyberConfig
import com.example.model.CyberStreamStats
import com.example.ui.theme.*


@Composable
fun CyberAudioStudioModal(
    config: CyberConfig,
    stats: CyberStreamStats,
    onMicGainChanged: (Float) -> Unit,
    onSpeakerVolumeChanged: (Float) -> Unit,
    onAudioRoutingChanged: (AudioRouting) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyberCyan, RoundedCornerShape(20.dp))
                .testTag("cyber_audio_studio_modal")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
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
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = StudioPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "Audio Studio & Routing",
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = StudioTextPrimary
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp).testTag("btn_close_audio_modal")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = StudioTextSecondary
                        )
                    }
                }

                // Section 1: Microphone Stream (Phone to PC)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioSurfaceElevated)
                        .border(1.dp, StudioBorderSubtle, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = StudioPrimary, modifier = Modifier.size(16.dp))
                            Text("Phone Microphone", fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = StudioTextPrimary)
                        }
                        Text(
                            text = if (config.isMicMuted) "MUTED" else "${String.format("%.1f", stats.micLevelDb)} dB",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (config.isMicMuted) StudioDanger else StudioSuccess,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Live VU Meter Bar
                    val normVu = if (config.isMicMuted) 0f else ((stats.micLevelDb + 60f) / 60f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(StudioDarkBg)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(normVu)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (normVu > 0.85f) StudioDanger else StudioSuccess)
                        )
                    }

                    // Mic Gain Slider
                    Text(
                        text = "Mic Gain: ${String.format("%.1f", config.micGain)}x",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        color = StudioTextSecondary
                    )
                    Slider(
                        value = config.micGain,
                        onValueChange = onMicGainChanged,
                        valueRange = 0.2f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = StudioPrimary,
                            activeTrackColor = StudioPrimary,
                            inactiveTrackColor = StudioBorder
                        ),
                        modifier = Modifier.testTag("slider_mic_gain")
                    )
                }

                // Section 2: Speaker Output (PC System Audio to Phone Speaker)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioSurfaceElevated)
                        .border(1.dp, StudioBorderSubtle, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = StudioPrimary, modifier = Modifier.size(16.dp))
                            Text("Phone Speaker Receiver", fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = StudioTextPrimary)
                        }
                        Text(
                            text = if (config.isSpeakerEnabled) "ACTIVE" else "OFF",
                            fontFamily = FontFamily.Default,
                            fontSize = 11.sp,
                            color = if (config.isSpeakerEnabled) StudioSuccess else StudioTextMuted,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Phone acts as wireless PC speaker/headphones over Wi-Fi",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        color = StudioTextMuted
                    )

                    // Speaker Volume Slider
                    Text(
                        text = "Speaker Volume: ${(config.speakerVolume * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        color = StudioTextSecondary
                    )
                    Slider(
                        value = config.speakerVolume,
                        onValueChange = onSpeakerVolumeChanged,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = StudioPrimary,
                            activeTrackColor = StudioPrimary,
                            inactiveTrackColor = StudioBorder
                        ),
                        modifier = Modifier.testTag("slider_speaker_volume")
                    )

                    // Active Connected Audio Device Indicator
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioDarkBg)
                            .border(1.dp, StudioBorderSubtle, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Connected Audio Device", fontSize = 11.sp, fontFamily = FontFamily.Default, color = StudioTextSecondary)
                        Text(
                            text = stats.audioOutputDevice,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.SemiBold,
                            color = if (stats.hasHeadphones) StudioSuccess else StudioTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    // Audio Routing Buttons (Auto / Headphones vs Loudspeaker vs Earpiece)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isAuto = config.audioRouting == AudioRouting.AUTO
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isAuto) StudioPrimary else StudioDarkBg)
                                .border(1.dp, if (isAuto) StudioPrimary else StudioBorderSubtle, RoundedCornerShape(8.dp))
                                .clickable { onAudioRoutingChanged(AudioRouting.AUTO) }
                                .padding(vertical = 9.dp)
                                .testTag("btn_routing_auto"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🎧 Auto", fontSize = 11.sp, fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, color = if (isAuto) Color.White else StudioTextSecondary)
                        }

                        val isSpeaker = config.audioRouting == AudioRouting.SPEAKERPHONE
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSpeaker) StudioPrimary else StudioDarkBg)
                                .border(1.dp, if (isSpeaker) StudioPrimary else StudioBorderSubtle, RoundedCornerShape(8.dp))
                                .clickable { onAudioRoutingChanged(AudioRouting.SPEAKERPHONE) }
                                .padding(vertical = 9.dp)
                                .testTag("btn_routing_speaker"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🔊 Speaker", fontSize = 11.sp, fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, color = if (isSpeaker) Color.White else StudioTextSecondary)
                        }

                        val isEarpiece = config.audioRouting == AudioRouting.EARPIECE
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isEarpiece) StudioPrimary else StudioDarkBg)
                                .border(1.dp, if (isEarpiece) StudioPrimary else StudioBorderSubtle, RoundedCornerShape(8.dp))
                                .clickable { onAudioRoutingChanged(AudioRouting.EARPIECE) }
                                .padding(vertical = 9.dp)
                                .testTag("btn_routing_earpiece"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("👂 Earpiece", fontSize = 11.sp, fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, color = if (isEarpiece) Color.White else StudioTextSecondary)
                        }
                    }
                }
            }
        }
    }
}
