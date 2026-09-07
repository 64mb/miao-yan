package com.tw93.miaoyan.android.data

import android.net.Uri
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.LibraryDirectoryListing
import com.tw93.miaoyan.android.model.LibraryFolder
import com.tw93.miaoyan.android.model.OpenNote
import com.tw93.miaoyan.android.model.TrashedNote

/** UI boundary: canonical mutations delegate to LocalLibraryRepository; Room is derived only. */
data class FolderMutationResult(
    val oldRelativePath: String,
    val newRelativePath: String?,
)

interface LibraryRepository {
    /** Permanently prevents demo seeding before Import or Git claims an empty library. */
    suspend fun claimForExternalInitialization()

    suspend fun scan(): List<LibraryNote>

    suspend fun listDirectory(relativePath: String): LibraryDirectoryListing

    suspend fun search(query: String): List<LibraryNote>

    suspend fun open(note: LibraryNote): OpenNote

    suspend fun createNote(folderRelativePath: String, inputName: String): OpenNote

    suspend fun createFolder(parentRelativePath: String, inputName: String): LibraryFolder

    suspend fun rename(note: LibraryNote, inputName: String): LibraryNote

    suspend fun renameFolder(folder: LibraryFolder, inputName: String): FolderMutationResult

    suspend fun moveToTrash(note: LibraryNote)

    suspend fun moveFolderToTrash(folder: LibraryFolder)

    suspend fun listTrash(): List<TrashedNote>

    suspend fun restore(trashed: TrashedNote): RestoreResult

    suspend fun permanentlyDelete(trashed: TrashedNote)

    suspend fun save(snapshot: OpenNote, newText: String): OpenNote

    suspend fun importFrom(treeUri: Uri): TransferResult

    suspend fun exportTo(treeUri: Uri): TransferResult

    suspend fun backlinks(noteTitle: String): List<LibraryNote>

    suspend fun outlinks(noteId: String): List<String>

    suspend fun setPinned(relativePath: String, pinned: Boolean)

    suspend fun pinnedNotes(): List<LibraryNote>
}
