package com.example.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val isUpdateAvailable: Boolean = false,
    val currentVersion: String = "1.0.0",
    val latestVersion: String = "",
    val releaseTitle: String = "",
    val releaseNotes: String = "",
    val apkDownloadUrl: String = "",
    val msiDownloadUrl: String = "",
    val releaseUrl: String = ""
)

sealed class UpdateDownloadState {
    object Idle : UpdateDownloadState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : UpdateDownloadState()
    data class Downloaded(val apkFile: File) : UpdateDownloadState()
    data class Error(val message: String) : UpdateDownloadState()
}

object CyberUpdateManager {

    private const val GITHUB_REPO = "SUBHOJITPAUL797/DASMO-CYBER-CAPTURE"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    suspend fun checkForUpdates(currentVersion: String = "1.0.0"): AppUpdateInfo = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(GITHUB_API_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "DASMO-CYBER-CAPTURE-Android")
                connectTimeout = 6000
                readTimeout = 6000
            }

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val tagName = json.optString("tag_name", "").replace("v", "").trim()
                val title = json.optString("name", "New Update Available")
                val notes = json.optString("body", "Bug fixes and performance improvements.")
                val htmlUrl = json.optString("html_url", "https://github.com/$GITHUB_REPO/releases")

                var apkUrl = ""
                var msiUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "").lowercase()
                        val downloadUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = downloadUrl
                        } else if (name.endsWith(".msi")) {
                            msiUrl = downloadUrl
                        }
                    }
                }

                if (apkUrl.isEmpty()) {
                    apkUrl = htmlUrl
                }

                val hasUpdate = isNewerVersion(tagName, currentVersion)

                return@withContext AppUpdateInfo(
                    isUpdateAvailable = hasUpdate,
                    currentVersion = currentVersion,
                    latestVersion = tagName,
                    releaseTitle = title,
                    releaseNotes = notes,
                    apkDownloadUrl = apkUrl,
                    msiDownloadUrl = msiUrl,
                    releaseUrl = htmlUrl
                )
            } else {
                Log.w("CyberUpdateManager", "GitHub Releases API returned code: ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Log.e("CyberUpdateManager", "Error checking for updates", e)
        } finally {
            conn?.disconnect()
        }

        return@withContext AppUpdateInfo(
            isUpdateAvailable = false,
            currentVersion = currentVersion
        )
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        if (latest.isEmpty()) return false
        try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {
            return latest != current
        }
        return false
    }

    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        fileName: String = "DASMO_CYBER_CAPTURE_update.apk",
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var currentUrl = apkUrl
            var redirects = 0
            val maxRedirects = 5

            while (redirects < maxRedirects) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "DASMO-CYBER-CAPTURE-Android")
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 || code == 308) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (newUrl.isNullOrEmpty()) break
                    currentUrl = newUrl
                    redirects++
                } else {
                    break
                }
            }

            if (connection == null || connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("CyberUpdateManager", "Server returned HTTP ${connection?.responseCode}")
                return@withContext null
            }

            val fileLength = connection.contentLengthLong

            val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val destinationFile = File(targetDir, fileName)
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            inputStream = connection.inputStream
            outputStream = FileOutputStream(destinationFile)

            val buffer = ByteArray(16384)
            var bytesRead: Int
            var totalBytesDownloaded = 0L
            var lastReportedPercent = -1

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesDownloaded += bytesRead

                val percent = if (fileLength > 0) {
                    ((totalBytesDownloaded * 100) / fileLength).toInt().coerceIn(0, 100)
                } else {
                    0
                }

                if (percent != lastReportedPercent || totalBytesDownloaded == fileLength) {
                    lastReportedPercent = percent
                    onProgress(totalBytesDownloaded, fileLength, percent)
                }
            }

            outputStream.flush()
            Log.i("CyberUpdateManager", "APK download complete: ${destinationFile.absolutePath}, size: ${destinationFile.length()} bytes")
            return@withContext destinationFile
        } catch (e: Exception) {
            Log.e("CyberUpdateManager", "Error downloading APK", e)
            return@withContext null
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("CyberUpdateManager", "Failed to open install permission settings", e)
            }
        }
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0) {
            Log.e("CyberUpdateManager", "Cannot install: APK file does not exist or is empty")
            return false
        }

        if (!canRequestPackageInstalls(context)) {
            Log.w("CyberUpdateManager", "REQUEST_INSTALL_PACKAGES not granted, redirecting to settings")
            openInstallPermissionSettings(context)
            return false
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e("CyberUpdateManager", "Failed to trigger package installer", e)
            return false
        }
    }

    fun openUpdateLink(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CyberUpdateManager", "Could not open update link", e)
        }
    }
}
