package com.tw93.miaoyan.android.data.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "index_root")
data class IndexRootEntity(
    @PrimaryKey val id: Int = SingleRootId,
    val rootIdentity: String,
) {
    companion object {
        const val SingleRootId = 1
    }
}

@Entity(
    tableName = "indexed_notes",
    indices = [
        Index(value = ["relativePath"], unique = true),
        Index(value = ["titleKey"]),
    ],
)
data class IndexedNoteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
    val relativePath: String,
    val title: String,
    val titleKey: String,
    val snippet: String,
    @ColumnInfo(name = "body")
    val bodyForFts: String,
    val modifiedAtMillis: Long,
    val sizeBytes: Long,
    val contentHash: String,
    val isPinned: Boolean,
)

@Fts4(
    contentEntity = IndexedNoteEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
)
@Entity(tableName = "indexed_notes_fts")
data class IndexedNoteFtsEntity(
    val relativePath: String,
    val title: String,
    @ColumnInfo(name = "body")
    val bodyForFts: String,
)

@Entity(
    tableName = "wikilinks",
    primaryKeys = ["sourceRowId", "targetKey"],
    foreignKeys = [
        ForeignKey(
            entity = IndexedNoteEntity::class,
            parentColumns = ["rowid"],
            childColumns = ["sourceRowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceRowId"), Index("targetKey")],
)
data class WikilinkEntity(
    val sourceRowId: Long,
    val target: String,
    val targetKey: String,
)
