package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.CyberBlack
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberGreen
import com.example.ui.theme.CyberRed
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.updater.AppUpdateInfo
import com.example.updater.CyberUpdateManager
import com.example.updater.UpdateDownloadState
import java.io.File
import java.util.Locale

@Composable
fun CyberUpdateModal(
    updateInfo: AppUpdateInfo,
    downloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    onStartDownload: (String) -> Unit = {},
    onInstallApk: (File?) -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = {
        // Prevent accidental dismiss while download is actively in progress
        if (downloadState !is UpdateDownloadState.Downloading) {
            onDismiss()
        }
    }) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyberCyan, RoundedCornerShape(20.dp))
                .testTag("cyber_update_modal")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
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
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "CYBER UPDATE // OTA",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = CyberCyan
                        )
                    }

                    if (downloadState !is UpdateDownloadState.Downloading) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(28.dp).testTag("btn_close_update_modal")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = CyberTextSecondary
                            )
                        }
                    }
                }

                // Version Badge Card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CyberSurfaceVariant)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "CURRENT: v${updateInfo.currentVersion}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyberTextMuted
                        )
                        Text(
                            text = "LATEST: v${updateInfo.latestVersion}",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = CyberGreen
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CyberGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "OTA READY",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = CyberGreen
                        )
                    }
                }

                // Release Title & Changelog Box
                Text(
                    text = updateInfo.releaseTitle.ifEmpty { "DASMO CYBER CAPTURE Update" },
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextPrimary
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberBlack.copy(alpha = 0.6f))
                        .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = updateInfo.releaseNotes.ifEmpty { "• Ultra-low latency camera pipeline\n• In-app OTA downloader & installer\n• Bug fixes and stability enhancements" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyberTextSecondary,
                        lineHeight = 16.sp
                    )
                }

                // In-App Download & Update Engine Section
                when (downloadState) {
                    is UpdateDownloadState.Downloading -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CyberSurfaceVariant)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "DOWNLOADING UPDATE...",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = CyberCyan
                                )
                                Text(
                                    text = "${downloadState.progressPercent}%",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = CyberCyan
                                )
                            }

                            LinearProgressIndicator(
                                progress = { (downloadState.progressPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = CyberCyan,
                                trackColor = CyberBlack
                            )

                            val downloadedMb = String.format(Locale.US, "%.1f", downloadState.bytesDownloaded / (1024f * 1024f))
                            val totalMb = if (downloadState.totalBytes > 0) {
                                String.format(Locale.US, "%.1f MB", downloadState.totalBytes / (1024f * 1024f))
                            } else {
                                "..."
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "$downloadedMb MB / $totalMb",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = CyberTextSecondary
                                )
                                Text(
                                    text = "Direct In-App Stream",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = CyberTextMuted
                                )
                            }

                            TextButton(
                                onClick = onCancelDownload,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("Cancel Download", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CyberRed)
                            }
                        }
                    }

                    is UpdateDownloadState.Downloaded -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CyberGreen.copy(alpha = 0.12f))
                                .border(1.dp, CyberGreen.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = CyberGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "UPDATE DOWNLOADED & READY!",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = CyberGreen
                                )
                            }

                            Button(
                                onClick = { onInstallApk(downloadState.apkFile) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberGreen,
                                    contentColor = CyberBlack
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_install_downloaded_apk")
                            ) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("INSTALL UPDATE NOW", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            if (!CyberUpdateManager.canRequestPackageInstalls(context)) {
                                OutlinedButton(
                                    onClick = { CyberUpdateManager.openInstallPermissionSettings(context) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text("Enable 'Install Unknown Apps' Permission", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberCyan)
                                }
                            }
                        }
                    }

                    is UpdateDownloadState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CyberRed.copy(alpha = 0.12f))
                                .border(1.dp, CyberRed.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CyberRed, modifier = Modifier.size(16.dp))
                                Text("In-App Download Error", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CyberRed, fontWeight = FontWeight.Bold)
                            }
                            Text(downloadState.message, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CyberTextSecondary)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val url = updateInfo.apkDownloadUrl.ifEmpty { updateInfo.releaseUrl }
                                        onStartDownload(url)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberBlack),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text("Retry", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val url = updateInfo.apkDownloadUrl.ifEmpty { updateInfo.releaseUrl }
                                        CyberUpdateManager.openUpdateLink(context, url)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text("Browser", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CyberCyan)
                                }
                            }
                        }
                    }

                    is UpdateDownloadState.Idle -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val url = updateInfo.apkDownloadUrl.ifEmpty { updateInfo.releaseUrl }
                                    if (url.endsWith(".apk")) {
                                        onStartDownload(url)
                                    } else {
                                        CyberUpdateManager.openUpdateLink(context, url)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = CyberBlack
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().testTag("btn_download_apk_update")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("DOWNLOAD & UPDATE IN-APP", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            if (updateInfo.msiDownloadUrl.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        CyberUpdateManager.openUpdateLink(context, updateInfo.msiDownloadUrl)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyberSurfaceVariant,
                                        contentColor = CyberCyan
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("btn_download_msi_update")
                                ) {
                                    Text("Download Windows MSI Installer", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }

                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("Remind Me Later", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CyberTextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}
