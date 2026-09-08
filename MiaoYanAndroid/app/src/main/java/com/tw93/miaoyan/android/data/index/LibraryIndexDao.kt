package com.tw93.miaoyan.android.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface LibraryIndexDao {
    @Query("SELECT * FROM index_root WHERE id = 1")
    suspend fun root(): IndexRootEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setRoot(root: IndexRootEntity)

    @Query("DELETE FROM index_root")
    suspend fun clearRoot()

    @Query("SELECT * FROM indexed_notes")
    suspend fun allNotes(): List<IndexedNoteEntity>

    @Query("SELECT * FROM indexed_notes WHERE relativePath = :relativePath LIMIT 1")
    suspend fun noteByPath(relativePath: String): IndexedNoteEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNote(note: IndexedNoteEntity): Long

    @Update
    suspend fun updateNote(note: IndexedNoteEntity)

    @Query("DELETE FROM indexed_notes WHERE rowid IN (:rowIds)")
    suspend fun deleteNotes(rowIds: List<Long>)

    @Query("DELETE FROM indexed_notes")
    suspend fun clearNotes()

    @Query("DELETE FROM wikilinks WHERE sourceRowId = :sourceRowId")
    suspend fun clearLinks(sourceRowId: Long)

    @Query("DELETE FROM wikilinks")
    suspend fun clearAllLinks()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<WikilinkEntity>)

    @Query(
        """
        SELECT indexed_notes.* FROM indexed_notes
        JOIN indexed_notes_fts ON indexed_notes_fts.rowid = indexed_notes.rowid
        WHERE indexed_notes_fts MATCH :matchQuery
        ORDER BY indexed_notes.modifiedAtMillis DESC, indexed_notes.relativePath COLLATE NOCASE
        """,
    )
    suspend fun search(matchQuery: String): List<IndexedNoteEntity>

    @Query(
        """
        SELECT DISTINCT indexed_notes.* FROM indexed_notes
        JOIN wikilinks ON wikilinks.sourceRowId = indexed_notes.rowid
        WHERE wikilinks.targetKey = :titleKey
        ORDER BY indexed_notes.relativePath COLLATE NOCASE
        """,
    )
    suspend fun backlinks(titleKey: String): List<IndexedNoteEntity>

    @Query("SELECT target FROM wikilinks WHERE sourceRowId = :sourceRowId ORDER BY target COLLATE NOCASE")
    suspend fun outlinks(sourceRowId: Long): List<String>

    @Query("UPDATE indexed_notes SET isPinned = :pinned WHERE relativePath = :relativePath")
    suspend fun setPinned(relativePath: String, pinned: Boolean)

    @Query(
        "SELECT * FROM indexed_notes WHERE isPinned = 1 " +
            "ORDER BY modifiedAtMillis DESC, relativePath COLLATE NOCASE",
    )
    suspend fun pinnedNotes(): List<IndexedNoteEntity>
}
