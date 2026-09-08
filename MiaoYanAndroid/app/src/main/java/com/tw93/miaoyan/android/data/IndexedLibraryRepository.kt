package com.tw93.miaoyan.android.data

import android.net.Uri
import android.util.Log
import com.tw93.miaoyan.android.data.index.IndexedDocument
import com.tw93.miaoyan.android.data.index.IndexText
import com.tw93.miaoyan.android.data.index.IndexReconciliationResult
import com.tw93.miaoyan.android.data.index.LibrarySearchIndex
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.LibraryDirectoryListing
import com.tw93.miaoyan.android.model.LibraryFolder
import com.tw93.miaoyan.android.model.OpenNote
import com.tw93.miaoyan.android.model.TrashedNote
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex

/**
 * Adds a rebuildable search projection around the sole canonical repository.
 *
 * Every content mutation reaches LocalLibraryRepository first. A Room failure can therefore
 * discard only derived search/link rows and never changes the app-private library.
 */
class IndexedLibraryRepository(
    private val canonical: LocalLibraryRepository,
    private val index: LibrarySearchIndex,
    private val pins: LocalPinStore,
) : LibraryRepository {
    private val operationMutex = Mutex()
    private val rootIdentity: String
        get() = canonical.rootIdentity

    override suspend fun claimForExternalInitialization() = serialized {
        canonical.claimForExternalInitialization()
    }

    override suspend fun scan(): List<LibraryNote> = serialized { scanLocked() }

    override suspend fun listDirectory(relativePath: String): LibraryDirectoryListing = serialized {
        canonical.listDirectory(relativePath)
    }

    override suspend fun search(query: String): List<LibraryNote> =
        index.search(rootIdentity, query)

    override suspend fun open(note: LibraryNote): OpenNote = serialized {
        canonical.open(note).also { updateIndexBestEffort(it) }
    }

    override suspend fun createNote(folderRelativePath: String, inputName: String): OpenNote = serialized {
        canonical.createNote(folderRelativePath, inputName).also { created ->
            updateIndexBestEffort(created)
        }
    }

    override suspend fun createFolder(parentRelativePath: String, inputName: String): LibraryFolder = serialized {
        canonical.createFolder(parentRelativePath, inputName)
    }

    override suspend fun rename(note: LibraryNote, inputName: String): LibraryNote = serialized {
        val wasPinned = note.relativePath in pins.paths()
        canonical.rename(note, inputName).also { renamed ->
            if (wasPinned && renamed.relativePath != note.relativePath) {
                pins.setPinned(note.relativePath, false)
                pins.setPinned(renamed.relativePath, true)
            }
            scanLocked()
        }
    }

    override suspend fun renameFolder(folder: LibraryFolder, inputName: String): FolderMutationResult = serialized {
        canonical.renameFolder(folder, inputName).also { scanLocked() }
    }

    override suspend fun moveToTrash(note: LibraryNote) = serialized {
        canonical.moveToTrash(note)
        pins.setPinned(note.relativePath, false)
        scanLocked()
        Unit
    }

    override suspend fun moveFolderToTrash(folder: LibraryFolder) = serialized {
        canonical.moveFolderToTrash(folder)
        scanLocked()
        Unit
    }

    override suspend fun listTrash(): List<TrashedNote> = serialized {
        canonical.listTrash()
    }

    override suspend fun restore(trashed: TrashedNote): RestoreResult = serialized {
        canonical.restore(trashed).also { scanLocked() }
    }

    override suspend fun permanentlyDelete(trashed: TrashedNote) = serialized {
        canonical.permanentlyDelete(trashed)
        scanLocked()
        Unit
    }

    override suspend fun save(snapshot: OpenNote, newText: String): OpenNote = serialized {
        canonical.save(snapshot, newText).also { saved ->
            updateIndexBestEffort(saved)
        }
    }

    override suspend fun importFrom(treeUri: Uri): TransferResult = serialized {
        canonical.importFrom(treeUri).also { scanLocked() }
    }

    override suspend fun exportTo(treeUri: Uri): TransferResult = serialized {
        canonical.exportTo(treeUri)
    }

    override suspend fun backlinks(noteTitle: String): List<LibraryNote> =
        index.backlinks(rootIdentity, noteTitle)

    override suspend fun outlinks(noteId: String): List<String> =
        index.outlinks(rootIdentity, noteId)

    override suspend fun setPinned(relativePath: String, pinned: Boolean) {
        require(NotePathPolicy.isNote(relativePath)) { "The pinned note path is invalid." }
        pins.setPinned(relativePath, pinned)
        updatePinProjectionBestEffort(relativePath, pinned)
    }

    override suspend fun pinnedNotes(): List<LibraryNote> = serialized {
        val pinnedPaths = pins.paths()
        canonical.scan().filter { it.relativePath in pinnedPaths }
    }

    private suspend fun scanLocked(): List<LibraryNote> {
        val notes = canonical.scan()
        try {
            val stale = index.notesNeedingContent(rootIdentity, notes)
            val loaded = stale.mapNotNull { note ->
                try {
                    IndexText.document(canonical.open(note))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.w(LogTag, "Could not index ${note.relativePath}", error)
                    null
                }
            }
            val pinnedPaths = pins.paths()
            val result = index.reconcileScan(rootIdentity, notes, loaded, pinnedPaths)
            if (result == IndexReconciliationResult.FullRebuildRequired) {
                val allDocuments = loadDocuments(notes)
                if (allDocuments.size == notes.size) {
                    index.rebuild(rootIdentity, notes, allDocuments, pinnedPaths)
                } else {
                    Log.w(LogTag, "Full index rebuild deferred because not every note could be read")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(LogTag, "The derived search index could not be reconciled", error)
        }
        return notes
    }

    private suspend fun loadDocuments(notes: List<LibraryNote>): List<IndexedDocument> =
        notes.mapNotNull { note ->
            try {
                IndexText.document(canonical.open(note))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(LogTag, "Could not read ${note.relativePath} for a full index rebuild", error)
                null
            }
        }

    private suspend fun updateIndexBestEffort(openNote: OpenNote) {
        try {
            val pinned = openNote.note.relativePath in pins.paths()
            index.upsert(rootIdentity, IndexText.document(openNote), pinned)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(LogTag, "The derived search index could not be updated", error)
        }
    }

    private suspend fun updatePinProjectionBestEffort(relativePath: String, pinned: Boolean) {
        try {
            index.setPinned(rootIdentity, relativePath, pinned)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(LogTag, "The derived pin projection could not be updated", error)
        }
    }

    private suspend fun <T> serialized(operation: suspend () -> T): T {
        operationMutex.lock()
        return try {
            operation()
        } finally {
            operationMutex.unlock()
        }
    }

    private companion object {
        const val LogTag = "MiaoYanIndex"
    }
}
