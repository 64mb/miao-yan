package com.tw93.miaoyan.android.data

import android.content.Context
import com.tw93.miaoyan.android.data.index.RoomLibrarySearchIndex

/** One repository graph shared by UI and background sync projection refreshes. */
object LibraryRepositoryProvider {
    @Volatile
    private var instance: LibraryRepository? = null

    fun get(context: Context): LibraryRepository = instance ?: synchronized(this) {
        instance ?: run {
            val application = context.applicationContext
            val pins = LocalPinStore(application)
            val pathMetadata = CrashSafeLibraryPathMetadata(
                pins = pins,
                journalFile = java.io.File(application.noBackupFilesDir, "library-path-mutation.v1"),
            )
            IndexedLibraryRepository(
                canonical = LocalLibraryRepository(application, pathMetadata = pathMetadata),
                index = RoomLibrarySearchIndex(application),
                pins = pins,
            )
        }.also { instance = it }
    }
}
