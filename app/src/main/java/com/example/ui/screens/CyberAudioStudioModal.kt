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
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.AudioRouting
import com.example.model.CyberConfig
import com.example.model.CyberStreamStats
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberGreen
import com.example.ui.theme.CyberRed
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary

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
                            tint = CyberCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "AUDIO STUDIO // MIC & SPEAKER",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = CyberCyan
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp).testTag("btn_close_audio_modal")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = CyberTextSecondary
                        )
                    }
                }

                // Section 1: Microphone Stream (Phone to PC)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CyberSurfaceVariant)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                            Text("PHONE MIC TRANSMITTER", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = CyberTextPrimary)
                        }
                        Text(
                            text = if (config.isMicMuted) "MUTED" else "${String.format("%.1f", stats.micLevelDb)} dB",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (config.isMicMuted) CyberRed else CyberGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Live VU Meter Bar
                    val normVu = if (config.isMicMuted) 0f else ((stats.micLevelDb + 60f) / 60f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x33263554))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(normVu)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (normVu > 0.85f) CyberRed else CyberGreen)
                        )
                    }

                    // Mic Gain Slider
                    Text(
                        text = "Microphone Gain: ${String.format("%.1f", config.micGain)}x",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyberTextSecondary
                    )
                    Slider(
                        value = config.micGain,
                        onValueChange = onMicGainChanged,
                        valueRange = 0.2f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = CyberCyan,
                            activeTrackColor = CyberCyan,
                            inactiveTrackColor = CyberBorder
                        ),
                        modifier = Modifier.testTag("slider_mic_gain")
                    )
                }

                // Section 2: Speaker Output (PC System Audio to Phone Speaker)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CyberSurfaceVariant)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = CyberGreen, modifier = Modifier.size(16.dp))
                            Text("PHONE SPEAKER RECEIVER", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = CyberTextPrimary)
                        }
                        Text(
                            text = if (config.isSpeakerEnabled) "ACTIVE" else "OFF",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (config.isSpeakerEnabled) CyberGreen else CyberTextMuted,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Phone acts as wireless PC speaker/headphones over Wi-Fi",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyberTextMuted
                    )

                    // Speaker Volume Slider
                    Text(
                        text = "Speaker Volume: ${(config.speakerVolume * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyberTextSecondary
                    )
                    Slider(
                        value = config.speakerVolume,
                        onValueChange = onSpeakerVolumeChanged,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = CyberGreen,
                            activeTrackColor = CyberGreen,
                            inactiveTrackColor = CyberBorder
                        ),
                        modifier = Modifier.testTag("slider_speaker_volume")
                    )

                    // Audio Routing Buttons (Speakerphone vs Earpiece)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isSpeaker = config.audioRouting == AudioRouting.SPEAKERPHONE
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSpeaker) CyberGreen.copy(alpha = 0.2f) else CyberBorder.copy(alpha = 0.4f))
                                .border(1.dp, if (isSpeaker) CyberGreen else CyberBorder, RoundedCornerShape(8.dp))
                                .clickable { onAudioRoutingChanged(AudioRouting.SPEAKERPHONE) }
                                .padding(vertical = 8.dp)
                                .testTag("btn_routing_speaker"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🔊 Loud Speaker", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (isSpeaker) CyberGreen else CyberTextSecondary)
                        }

                        val isEarpiece = config.audioRouting == AudioRouting.EARPIECE
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isEarpiece) CyberGreen.copy(alpha = 0.2f) else CyberBorder.copy(alpha = 0.4f))
                                .border(1.dp, if (isEarpiece) CyberGreen else CyberBorder, RoundedCornerShape(8.dp))
                                .clickable { onAudioRoutingChanged(AudioRouting.EARPIECE) }
                                .padding(vertical = 8.dp)
                                .testTag("btn_routing_earpiece"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("👂 Private Earpiece", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (isEarpiece) CyberGreen else CyberTextSecondary)
                        }
                    }
                }
            }
        }
    }
}
