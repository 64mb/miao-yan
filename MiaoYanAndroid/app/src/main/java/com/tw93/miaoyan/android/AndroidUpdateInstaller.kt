package com.tw93.miaoyan.android

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class AndroidUpdateInstaller(private val activity: MainActivity) {
    private val downloadManager = activity.getSystemService(DownloadManager::class.java)
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var receiverRegistered = false
    private var installerOpenedFor = NO_DOWNLOAD
    private var resolvingLatest = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, NO_DOWNLOAD)
            if (completedId == pendingDownloadId()) openInstallerIfReady(completedId)
        }
    }

    fun start() {
        if (!receiverRegistered) {
            activity.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }
        pendingDownloadId().takeIf { it != NO_DOWNLOAD }?.let(::openInstallerIfReady)
    }

    fun stop() {
        if (!receiverRegistered) return
        activity.unregisterReceiver(receiver)
        receiverRegistered = false
    }

    fun downloadLatest() {
        if (resolvingLatest) {
            toast(R.string.app_update_already_downloading)
            return
        }
        val existingId = pendingDownloadId()
        if (existingId != NO_DOWNLOAD) {
            when {
                pendingDownloadFileName() == null -> {
                    downloadManager.remove(existingId)
                    clearPendingDownload()
                }

                queryStatus(existingId) in listOf(
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED,
                ) -> {
                    toast(R.string.app_update_already_downloading)
                    return
                }

                else -> {
                    downloadManager.remove(existingId)
                    clearPendingDownload()
                }
            }
        }

        resolvingLatest = true
        activity.lifecycleScope.launch {
            try {
                enqueueDownload(AndroidReleaseApkResolver.latest())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toast(R.string.app_update_download_failed)
            } finally {
                resolvingLatest = false
            }
        }
    }

    private fun enqueueDownload(apk: VersionedApk) {
        val destination = updateFile(apk.fileName)
        if (destination.exists() && !destination.delete()) {
            toast(R.string.app_update_download_failed)
            return
        }

        val request = DownloadManager.Request(Uri.parse(apk.downloadUrl))
            .setTitle(activity.getString(R.string.app_update_download_title))
            .setDescription(activity.getString(R.string.app_update_download_detail))
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, apk.fileName)

        val id = runCatching { downloadManager.enqueue(request) }.getOrElse {
            toast(R.string.app_update_download_failed)
            return
        }
        preferences.edit()
            .putLong(PENDING_DOWNLOAD_KEY, id)
            .putString(PENDING_FILE_NAME_KEY, apk.fileName)
            .apply()
        installerOpenedFor = NO_DOWNLOAD
        toast(R.string.app_update_download_started)
    }

    private fun openInstallerIfReady(downloadId: Long) {
        when (queryStatus(downloadId)) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED,
            -> return

            DownloadManager.STATUS_SUCCESSFUL -> Unit
            else -> {
                clearPendingDownload()
                toast(R.string.app_update_download_failed)
                return
            }
        }
        if (installerOpenedFor == downloadId) return

        val fileName = pendingDownloadFileName()
        if (fileName == null) {
            clearPendingDownload()
            toast(R.string.app_update_download_failed)
            return
        }
        val apk = updateFile(fileName)
        if (!apk.isFile) {
            clearPendingDownload()
            toast(R.string.app_update_download_failed)
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(activity.packageManager) == null) {
            clearPendingDownload()
            toast(R.string.app_update_installer_unavailable)
            return
        }
        installerOpenedFor = downloadId
        clearPendingDownload()
        activity.startActivity(intent)
    }

    private fun queryStatus(downloadId: Long): Int? = runCatching {
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }.getOrNull()

    private fun pendingDownloadId(): Long = preferences.getLong(PENDING_DOWNLOAD_KEY, NO_DOWNLOAD)

    private fun pendingDownloadFileName(): String? = preferences.getString(PENDING_FILE_NAME_KEY, null)

    private fun clearPendingDownload() {
        preferences.edit().remove(PENDING_DOWNLOAD_KEY).remove(PENDING_FILE_NAME_KEY).apply()
    }

    private fun updateFile(fileName: String): File = File(
        requireNotNull(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
        fileName,
    )

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val PREFERENCES_NAME = "android_update"
        const val PENDING_DOWNLOAD_KEY = "pending_download_id"
        const val PENDING_FILE_NAME_KEY = "pending_file_name"
        const val NO_DOWNLOAD = -1L
    }
}

internal data class VersionedApk(val fileName: String, val downloadUrl: String)

internal object AndroidReleaseApkResolver {
    private const val RELEASE_URL = "https://api.github.com/repos/64mb/miao-yan/releases/latest"
    private val releaseTag = Regex("V[0-9]+\\.[0-9]+\\.[0-9]+")

    suspend fun latest(): VersionedApk = withContext(Dispatchers.IO) {
        val connection = URL(RELEASE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "MiaoYan-Android")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub release lookup failed: ${connection.responseCode}")
            }
            parseLatest(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    fun parseLatest(payload: String): VersionedApk {
        val release = JSONObject(payload)
        val tag = release.getString("tag_name")
        require(releaseTag.matches(tag)) { "Unsupported release tag: $tag" }
        val fileName = "MiaoYan-Android-$tag.apk"
        val downloadUrl = "https://github.com/64mb/miao-yan/releases/download/$tag/$fileName"
        val assets = release.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") != fileName || asset.optString("state") != "uploaded") continue
            require(asset.getString("browser_download_url") == downloadUrl) {
                "Release APK URL does not match its tag"
            }
            return VersionedApk(fileName, downloadUrl)
        }
        throw IOException("Versioned Android APK is missing from $tag")
    }
}
