package com.tw93.miaoyan.android.data

import android.content.Context
import com.tw93.miaoyan.android.data.index.RoomLibrarySearchIndex

/** One repository graph shared by UI and background sync projection refreshes. */
object LibraryRepositoryProvider {
    @Volatile
    private var instance: LibraryRepository? = null

    fun get(context: Context): LibraryRepository = instance ?: synchronized(this) {
        instance ?: IndexedLibraryRepository(
            canonical = LocalLibraryRepository(context.applicationContext),
            index = RoomLibrarySearchIndex(context.applicationContext),
            pins = LocalPinStore(context.applicationContext),
        ).also { instance = it }
    }
}
