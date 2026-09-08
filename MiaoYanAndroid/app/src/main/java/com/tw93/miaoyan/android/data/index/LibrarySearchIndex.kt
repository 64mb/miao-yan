package com.tw93.miaoyan.android.data.index

import com.tw93.miaoyan.android.model.LibraryNote

/** Replaceable derived-cache boundary. No caller may treat it as canonical storage. */
interface LibrarySearchIndex {
    suspend fun notesNeedingContent(rootIdentity: String, scanned: List<LibraryNote>): List<LibraryNote>

    suspend fun reconcileScan(
        rootIdentity: String,
        scanned: List<LibraryNote>,
        loaded: List<IndexedDocument>,
        pinnedPaths: Set<String>,
    ): IndexReconciliationResult

    suspend fun rebuild(
        rootIdentity: String,
        scanned: List<LibraryNote>,
        documents: List<IndexedDocument>,
        pinnedPaths: Set<String>,
    )

    suspend fun upsert(rootIdentity: String, document: IndexedDocument, pinned: Boolean)

    suspend fun search(rootIdentity: String, query: String): List<LibraryNote>

    suspend fun backlinks(rootIdentity: String, noteTitle: String): List<LibraryNote>

    suspend fun outlinks(rootIdentity: String, noteId: String): List<String>

    suspend fun setPinned(rootIdentity: String, relativePath: String, pinned: Boolean)

    suspend fun clear()
}

enum class IndexReconciliationResult {
    Current,
    FullRebuildRequired,
}

internal fun IndexedNoteEntity.toLibraryNote(): LibraryNote = LibraryNote(
    id = relativePath,
    relativePath = relativePath,
    displayName = relativePath.substringAfterLast('/'),
    modifiedAtMillis = modifiedAtMillis,
    sizeBytes = sizeBytes,
)
