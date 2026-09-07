package com.tw93.miaoyan.android.data

import android.net.Uri
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.OpenNote
import com.tw93.miaoyan.android.model.TrashedNote

/** UI boundary: canonical mutations delegate to LocalLibraryRepository; Room is derived only. */
interface LibraryRepository {
    suspend fun scan(): List<LibraryNote>

    suspend fun search(query: String): List<LibraryNote>

    suspend fun open(note: LibraryNote): OpenNote

    suspend fun createRootNote(inputName: String): OpenNote

    suspend fun rename(note: LibraryNote, inputName: String): LibraryNote

    suspend fun moveToTrash(note: LibraryNote)

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
