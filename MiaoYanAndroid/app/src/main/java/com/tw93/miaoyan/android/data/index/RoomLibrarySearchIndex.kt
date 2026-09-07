package com.tw93.miaoyan.android.data.index

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.tw93.miaoyan.android.model.LibraryNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class RoomLibrarySearchIndex(context: Context) : LibrarySearchIndex {
    private val applicationContext = context.applicationContext
    private val databaseMutex = Mutex()
    private var database = LibraryIndexDatabase.build(applicationContext)

    override suspend fun notesNeedingContent(
        rootIdentity: String,
        scanned: List<LibraryNote>,
    ): List<LibraryNote> = access { database ->
        ensureRoot(database, rootIdentity)
        val existingByPath = database.indexDao().allNotes().associateBy(IndexedNoteEntity::relativePath)
        scanned.filter { note -> needsContent(note, existingByPath[note.relativePath]) }
    }

    override suspend fun reconcileScan(
        rootIdentity: String,
        scanned: List<LibraryNote>,
        loaded: List<IndexedDocument>,
        pinnedPaths: Set<String>,
    ) = access { database ->
        ensureRoot(database, rootIdentity)
        database.withTransaction {
            val dao = database.indexDao()
            val existing = dao.allNotes()
            val existingByPath = existing.associateBy(IndexedNoteEntity::relativePath)
            val loadedByPath = loaded.associateBy { it.note.relativePath }
            val rows = scanned.mapNotNull { note ->
                val cached = existingByPath[note.relativePath]
                val indexed = loadedByPath[note.relativePath]
                when {
                    indexed != null -> indexed.toEntity(cached?.rowId ?: 0, note.relativePath in pinnedPaths)
                    cached != null && !needsContent(note, cached) -> cached.copy(
                        title = IndexText.titleForPath(note.relativePath),
                        titleKey = IndexText.normalizeTitle(IndexText.titleForPath(note.relativePath)),
                        modifiedAtMillis = note.modifiedAtMillis,
                        sizeBytes = note.sizeBytes,
                        isPinned = note.relativePath in pinnedPaths,
                    )
                    else -> null
                }
            }
            val retainedRowIds = rows.mapNotNull { row -> row.rowId.takeIf { it != 0L } }.toSet()
            val removed = existing.map(IndexedNoteEntity::rowId).filterNot(retainedRowIds::contains)
            if (removed.isNotEmpty()) dao.deleteNotes(removed)

            rows.forEach { row ->
                val rowId = if (row.rowId == 0L) dao.insertNote(row) else row.rowId.also { dao.updateNote(row) }
                loadedByPath[row.relativePath]?.let { document ->
                    replaceLinks(dao, rowId, document.links)
                }
            }
        }
    }

    override suspend fun upsert(rootIdentity: String, document: IndexedDocument, pinned: Boolean) =
        access { database ->
            ensureRoot(database, rootIdentity)
            database.withTransaction {
                val dao = database.indexDao()
                val existing = dao.noteByPath(document.note.relativePath)
                val row = document.toEntity(existing?.rowId ?: 0, pinned)
                val rowId = if (row.rowId == 0L) {
                    dao.insertNote(row)
                } else {
                    row.rowId.also { dao.updateNote(row) }
                }
                replaceLinks(dao, rowId, document.links)
            }
        }

    override suspend fun search(rootIdentity: String, query: String): List<LibraryNote> = access { database ->
        if (database.indexDao().root()?.rootIdentity != rootIdentity) return@access emptyList()
        val matchQuery = IndexText.ftsQuery(query) ?: return@access emptyList()
        database.indexDao().search(matchQuery).map(IndexedNoteEntity::toLibraryNote)
    }

    override suspend fun backlinks(rootIdentity: String, noteTitle: String): List<LibraryNote> =
        access { database ->
            if (database.indexDao().root()?.rootIdentity != rootIdentity) return@access emptyList()
            database.indexDao().backlinks(IndexText.normalizeTitle(noteTitle)).map(IndexedNoteEntity::toLibraryNote)
        }

    override suspend fun outlinks(rootIdentity: String, noteId: String): List<String> = access { database ->
        if (database.indexDao().root()?.rootIdentity != rootIdentity) return@access emptyList()
        val note = database.indexDao().noteByPath(noteId) ?: return@access emptyList()
        database.indexDao().outlinks(note.rowId)
    }

    override suspend fun setPinned(rootIdentity: String, relativePath: String, pinned: Boolean) =
        access { database ->
            if (database.indexDao().root()?.rootIdentity == rootIdentity) {
                database.indexDao().setPinned(relativePath, pinned)
            }
        }

    override suspend fun clear() = access { database ->
        database.withTransaction {
            database.indexDao().clearAllLinks()
            database.indexDao().clearNotes()
            database.indexDao().clearRoot()
        }
    }

    private fun needsContent(note: LibraryNote, cached: IndexedNoteEntity?): Boolean =
        cached == null ||
            note.modifiedAtMillis <= 0 ||
            cached.modifiedAtMillis <= 0 ||
            cached.modifiedAtMillis != note.modifiedAtMillis ||
            cached.sizeBytes != note.sizeBytes

    private suspend fun ensureRoot(database: LibraryIndexDatabase, rootIdentity: String) {
        database.withTransaction {
            val dao = database.indexDao()
            if (dao.root()?.rootIdentity == rootIdentity) return@withTransaction
            dao.clearAllLinks()
            dao.clearNotes()
            dao.clearRoot()
            dao.setRoot(IndexRootEntity(rootIdentity = rootIdentity))
        }
    }

    private suspend fun replaceLinks(
        dao: LibraryIndexDao,
        rowId: Long,
        links: List<WikilinkMetadata>,
    ) {
        dao.clearLinks(rowId)
        if (links.isNotEmpty()) {
            dao.insertLinks(links.map { WikilinkEntity(rowId, it.target, it.targetKey) })
        }
    }

    private fun IndexedDocument.toEntity(rowId: Long, pinned: Boolean): IndexedNoteEntity =
        IndexedNoteEntity(
            rowId = rowId,
            relativePath = note.relativePath,
            title = title,
            titleKey = titleKey,
            snippet = snippet,
            bodyForFts = body,
            modifiedAtMillis = note.modifiedAtMillis,
            sizeBytes = note.sizeBytes,
            contentHash = contentHash,
            isPinned = pinned,
        )

    private suspend fun <T> access(block: suspend (LibraryIndexDatabase) -> T): T =
        withContext(Dispatchers.IO) {
            databaseMutex.lock()
            try {
                try {
                    block(database)
                } catch (_: SQLiteException) {
                    recreateDatabase()
                    block(database)
                }
            } finally {
                databaseMutex.unlock()
            }
        }

    private fun recreateDatabase() {
        database.close()
        applicationContext.deleteDatabase(LibraryIndexDatabase.DatabaseName)
        database = LibraryIndexDatabase.build(applicationContext)
    }
}
