package com.tw93.miaoyan.android.model

data class LibraryNote(
    val id: String,
    val relativePath: String,
    val displayName: String,
    val modifiedAtMillis: Long,
    val sizeBytes: Long,
)

data class TrashedNote(
    val manifestId: String?,
    val trashRelativePath: String,
    val displayName: String,
    val originalRelativePath: String?,
    val deletedAtMillis: Long,
)

data class OpenNote(
    val note: LibraryNote,
    val text: String,
    val contentHash: String,
)
