package com.tw93.miaoyan.android.data.index

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.tw93.miaoyan.android.model.LibraryNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class RoomLibrarySearchIndex(
    context: Context,
    private val maintenancePolicy: IndexMaintenancePolicy = IndexMaintenancePolicy(),
) : LibrarySearchIndex {
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
    ): IndexReconciliationResult = access(
        afterRecreate = { IndexReconciliationResult.FullRebuildRequired },
    ) { database ->
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
        maintain(database, IndexMaintenancePolicy.totalSourceBytes(scanned.map(LibraryNote::sizeBytes)))
    }

    override suspend fun rebuild(
        rootIdentity: String,
        scanned: List<LibraryNote>,
        documents: List<IndexedDocument>,
        pinnedPaths: Set<String>,
    ) = access { _ ->
        val scannedPaths = scanned.map(LibraryNote::relativePath).toSet()
        val documentsByPath = documents.associateBy { it.note.relativePath }
        require(documentsByPath.keys == scannedPaths) {
            "A full index rebuild requires every scanned document."
        }
        recreateDatabase()
        val rebuiltDatabase = database
        rebuiltDatabase.withTransaction {
            val dao = rebuiltDatabase.indexDao()
            dao.setRoot(IndexRootEntity(rootIdentity = rootIdentity))
            scanned.forEach { note ->
                val document = checkNotNull(documentsByPath[note.relativePath])
                val rowId = dao.insertNote(document.toEntity(pinned = note.relativePath in pinnedPaths))
                replaceLinks(dao, rowId, document.links)
            }
        }
        checkpointWalIfNeeded(rebuiltDatabase)
    }

    override suspend fun upsert(rootIdentity: String, document: IndexedDocument, pinned: Boolean) =
        access { database ->
            ensureRoot(database, rootIdentity)
            database.withTransaction {
                val dao = database.indexDao()
                val existing = dao.noteByPath(document.note.relativePath)
                if (existing != null && existing.matches(document, pinned)) return@withTransaction
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

    private fun IndexedDocument.toEntity(rowId: Long = 0, pinned: Boolean): IndexedNoteEntity =
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

    private fun IndexedNoteEntity.matches(document: IndexedDocument, pinned: Boolean): Boolean =
        relativePath == document.note.relativePath &&
            title == document.title &&
            titleKey == document.titleKey &&
            snippet == document.snippet &&
            modifiedAtMillis == document.note.modifiedAtMillis &&
            sizeBytes == document.note.sizeBytes &&
            contentHash == document.contentHash &&
            isPinned == pinned

    private fun maintain(database: LibraryIndexDatabase, sourceTextBytes: Long): IndexReconciliationResult {
        checkpointWalIfNeeded(database)
        val stats = storageStats(database)
        return if (maintenancePolicy.shouldRebuild(stats, sourceTextBytes)) {
            IndexReconciliationResult.FullRebuildRequired
        } else {
            IndexReconciliationResult.Current
        }
    }

    private fun checkpointWalIfNeeded(database: LibraryIndexDatabase) {
        val before = storageStats(database)
        if (!maintenancePolicy.shouldCheckpoint(before)) return
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            cursor.moveToFirst()
        }
    }

    private fun storageStats(database: LibraryIndexDatabase): IndexStorageStats {
        val sqlite = database.openHelper.writableDatabase
        val path = applicationContext.getDatabasePath(LibraryIndexDatabase.DatabaseName)
        return IndexStorageStats(
            databaseBytes = path.length(),
            walBytes = java.io.File("${path.path}-wal").length(),
            pageSizeBytes = sqlite.longPragma("PRAGMA page_size"),
            pageCount = sqlite.longPragma("PRAGMA page_count"),
            freePageCount = sqlite.longPragma("PRAGMA freelist_count"),
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.longPragma(sql: String): Long =
        query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0 }

    private suspend fun <T> access(
        afterRecreate: (suspend () -> T)? = null,
        block: suspend (LibraryIndexDatabase) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            databaseMutex.lock()
            try {
                try {
                    block(database)
                } catch (_: SQLiteException) {
                    recreateDatabase()
                    afterRecreate?.invoke() ?: block(database)
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
