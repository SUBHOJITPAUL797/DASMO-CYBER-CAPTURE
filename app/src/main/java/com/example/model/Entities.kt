package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDevice(
    @PrimaryKey val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int = 8080,
    val token: String = "",
    val pairedAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long = System.currentTimeMillis(),
    val isAutoConnect: Boolean = true,
    val connectionType: String = "Wi-Fi LAN"
)

@Entity(tableName = "capture_sessions")
data class CaptureSessionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientName: String,
    val startTime: Long,
    val durationSeconds: Long,
    val totalFrames: Long,
    val avgFps: Float,
    val networkType: String,
    val bytesTransferred: Long
)
