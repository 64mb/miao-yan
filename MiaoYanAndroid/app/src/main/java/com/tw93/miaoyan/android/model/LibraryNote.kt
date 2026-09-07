package com.tw93.miaoyan.android.model

import android.net.Uri

data class LibraryNote(
    val documentId: String,
    val uri: Uri,
    val relativePath: String,
    val displayName: String,
    val modifiedAtMillis: Long,
    val sizeBytes: Long,
)

data class OpenNote(
    val note: LibraryNote,
    val text: String,
    val contentHash: String,
)
