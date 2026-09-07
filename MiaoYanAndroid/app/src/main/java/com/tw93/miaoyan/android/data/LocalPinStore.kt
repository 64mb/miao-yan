package com.tw93.miaoyan.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pinDataStore by preferencesDataStore(name = "miaoyan_local_pins")

/** Pins are user metadata, so their authority lives outside the rebuildable Room database. */
class LocalPinStore(private val context: Context) {
    suspend fun paths(): Set<String> = context.pinDataStore.data.map { it[PinnedPaths].orEmpty() }.first()

    suspend fun setPinned(relativePath: String, pinned: Boolean) {
        context.pinDataStore.edit { preferences ->
            val paths = preferences[PinnedPaths].orEmpty().toMutableSet()
            if (pinned) paths += relativePath else paths -= relativePath
            preferences[PinnedPaths] = paths
        }
    }

    private companion object {
        val PinnedPaths = stringSetPreferencesKey("relative_paths")
    }
}
