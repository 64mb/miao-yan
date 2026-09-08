package com.tw93.miaoyan.android.data

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.util.Locale

/** Grants another app read access to one validated note-local attachment. */
class LocalFileAttachmentOpener(private val scope: LocalImagePolicy.NoteAssetScope) {
    fun open(context: Context, rawUrl: String): Boolean {
        val fileName = LocalImagePolicy.fileNameForAttachmentUrl(rawUrl) ?: return false
        val file = LocalImagePolicy.resolveLocalAttachment(scope, fileName) ?: return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return false
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType(file.name))
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    private fun mimeType(fileName: String): String = when (
        fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    ) {
        "pdf" -> "application/pdf"
        "txt", "md", "markdown" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
