package com.tw93.miaoyan.android

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tw93.miaoyan.android.data.index.IndexText
import com.tw93.miaoyan.android.data.index.IndexedNoteEntity
import com.tw93.miaoyan.android.data.index.LibraryIndexDatabase
import com.tw93.miaoyan.android.data.index.WikilinkEntity
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibrarySearchIndexTest {
    private lateinit var database: LibraryIndexDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    @Throws(IOException::class)
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun ftsSearchCoversTitleNestedPathAndBodyAndTracksUpdates() = runBlocking {
        val dao = database.indexDao()
        val first = dao.insertNote(note(path = "Work/Alpha.md", body = "quiet body"))
        dao.insertNote(note(path = "Other/Beta.md", body = "contains telescope"))

        assertEquals(listOf("Work/Alpha.md"), dao.search(query("alp")).map { it.relativePath })
        assertEquals(listOf("Work/Alpha.md"), dao.search(query("work")).map { it.relativePath })
        assertEquals(listOf("Other/Beta.md"), dao.search(query("teles")).map { it.relativePath })

        dao.updateNote(note(rowId = first, path = "Work/Alpha.md", body = "now planetary"))
        assertEquals(emptyList<String>(), dao.search(query("quiet")).map { it.relativePath })
        assertEquals(listOf("Work/Alpha.md"), dao.search(query("planet")).map { it.relativePath })
    }

    @Test
    fun noteAndWikilinksRollBackTogetherAndCascadeOnDelete() = runBlocking {
        val dao = database.indexDao()
        try {
            database.withTransaction {
                val source = dao.insertNote(note(path = "Source.md", body = "[[Target]]"))
                dao.insertLinks(listOf(WikilinkEntity(source, "Target", "target")))
                error("force rollback")
            }
            fail("Expected transaction rollback")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        assertEquals(emptyList<IndexedNoteEntity>(), dao.allNotes())
        assertEquals(emptyList<IndexedNoteEntity>(), dao.backlinks("target"))

        val source = database.withTransaction {
            val rowId = dao.insertNote(note(path = "Source.md", body = "[[Target]]"))
            dao.insertLinks(listOf(WikilinkEntity(rowId, "Target", "target")))
            rowId
        }
        assertEquals(listOf("Source.md"), dao.backlinks("target").map { it.relativePath })
        assertEquals(listOf("Target"), dao.outlinks(source))

        dao.deleteNotes(listOf(source))
        assertEquals(emptyList<String>(), dao.backlinks("target").map { it.relativePath })
    }

    @Test
    fun derivedRowsCanBeClearedRebuiltAndPinsReprojected() = runBlocking {
        val dao = database.indexDao()
        dao.insertNote(note(path = "Pinned.md", body = "first").copy(isPinned = true))
        assertEquals(listOf("Pinned.md"), dao.pinnedNotes().map { it.relativePath })

        dao.clearNotes()
        assertEquals(emptyList<IndexedNoteEntity>(), dao.allNotes())
        dao.insertNote(note(path = "Pinned.md", body = "rebuilt").copy(isPinned = true))

        assertEquals(listOf("Pinned.md"), dao.search(query("rebuilt")).map { it.relativePath })
        assertEquals(listOf("Pinned.md"), dao.pinnedNotes().map { it.relativePath })
    }

    private fun note(
        rowId: Long = 0,
        path: String,
        body: String,
    ): IndexedNoteEntity {
        val title = IndexText.titleForPath(path)
        return IndexedNoteEntity(
            rowId = rowId,
            relativePath = path,
            title = title,
            titleKey = IndexText.normalizeTitle(title),
            snippet = IndexText.snippet(body),
            bodyForFts = body,
            modifiedAtMillis = 1,
            sizeBytes = body.length.toLong(),
            contentHash = "hash-${path.hashCode()}-${body.hashCode()}",
            isPinned = false,
        )
    }

    private fun query(raw: String): String = requireNotNull(IndexText.ftsQuery(raw))
}
