package com.tw93.miaoyan.android.data.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        IndexRootEntity::class,
        IndexedNoteEntity::class,
        IndexedNoteFtsEntity::class,
        WikilinkEntity::class,
    ],
    version = LibraryIndexDatabase.SchemaVersion,
    exportSchema = true,
)
abstract class LibraryIndexDatabase : RoomDatabase() {
    abstract fun indexDao(): LibraryIndexDao

    companion object {
        const val DatabaseName = "miaoyan_search.db"
        const val SchemaVersion = 1

        fun build(context: Context): LibraryIndexDatabase =
            Room.databaseBuilder(context, LibraryIndexDatabase::class.java, DatabaseName)
                // This database is derived exclusively from canonical files. An incompatible future
                // schema is deliberately discarded and repopulated by the next scan.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
