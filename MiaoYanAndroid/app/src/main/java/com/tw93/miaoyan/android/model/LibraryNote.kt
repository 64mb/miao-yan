package com.tw93.miaoyan.android.model

data class LibraryNote(
    val id: String,
    val relativePath: String,
    val displayName: String,
    val modifiedAtMillis: Long,
    val sizeBytes: Long,
)

data class LibraryFolder(
    val id: String,
    val relativePath: String,
    val displayName: String,
) {
    val isRoot: Boolean
        get() = relativePath.isEmpty()
}

data class LibraryDirectoryListing(
    val currentFolder: LibraryFolder,
    val folders: List<LibraryFolder>,
    val notes: List<LibraryNote>,
)

enum class LibraryItemKind {
    NOTE,
    FOLDER,
}

data class TrashedLibraryItem(
    val manifestId: String?,
    val trashRelativePath: String,
    val displayName: String,
    val originalRelativePath: String?,
    val deletedAtMillis: Long,
    val kind: LibraryItemKind = LibraryItemKind.NOTE,
)

typealias TrashedNote = TrashedLibraryItem

data class OpenNote(
    val note: LibraryNote,
    val text: String,
    val contentHash: String,
)
