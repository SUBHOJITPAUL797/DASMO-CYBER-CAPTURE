package com.example.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
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
