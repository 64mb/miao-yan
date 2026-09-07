package com.tw93.miaoyan.android.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/** One-shot SAF/Photo Picker URI adapter; no persisted permission is taken. */
class ContentUriAttachmentSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : AttachmentSource {
    override fun metadata(): AttachmentMetadata {
        val providerMime = resolver.getType(uri)?.substringBefore(';')?.trim()
        return resolver.query(uri, Projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else AttachmentMetadata(
                displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME),
                mimeType = providerMime,
                declaredSize = cursor.longOrNull(OpenableColumns.SIZE),
            )
        } ?: AttachmentMetadata(displayName = null, mimeType = providerMime, declaredSize = null)
    }

    override fun openStream(): InputStream =
        resolver.openInputStream(uri) ?: error("The selected file could not be opened.")

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index).takeIf { it >= 0 }
    }

    private companion object {
        val Projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}
