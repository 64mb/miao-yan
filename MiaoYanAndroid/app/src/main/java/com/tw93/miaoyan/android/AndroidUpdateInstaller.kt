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
import java.io.File

internal class AndroidUpdateInstaller(private val activity: MainActivity) {
    private val downloadManager = activity.getSystemService(DownloadManager::class.java)
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var receiverRegistered = false
    private var installerOpenedFor = NO_DOWNLOAD

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
        val existingId = pendingDownloadId()
        if (existingId != NO_DOWNLOAD) {
            when (queryStatus(existingId)) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                -> {
                    toast(R.string.app_update_already_downloading)
                    return
                }

                else -> downloadManager.remove(existingId)
            }
        }

        val destination = updateFile()
        if (destination.exists() && !destination.delete()) {
            toast(R.string.app_update_download_failed)
            return
        }

        val request = DownloadManager.Request(Uri.parse(LATEST_APK_URL))
            .setTitle(activity.getString(R.string.app_update_download_title))
            .setDescription(activity.getString(R.string.app_update_download_detail))
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)

        val id = runCatching { downloadManager.enqueue(request) }.getOrElse {
            toast(R.string.app_update_download_failed)
            return
        }
        preferences.edit().putLong(PENDING_DOWNLOAD_KEY, id).apply()
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

        val apk = updateFile()
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

    private fun clearPendingDownload() {
        preferences.edit().remove(PENDING_DOWNLOAD_KEY).apply()
    }

    private fun updateFile(): File = File(
        requireNotNull(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
        APK_FILE_NAME,
    )

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val LATEST_APK_URL =
            "https://github.com/64mb/miao-yan/releases/latest/download/MiaoYan-Android.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val APK_FILE_NAME = "MiaoYan-Android.apk"
        const val PREFERENCES_NAME = "android_update"
        const val PENDING_DOWNLOAD_KEY = "pending_download_id"
        const val NO_DOWNLOAD = -1L
    }
}
