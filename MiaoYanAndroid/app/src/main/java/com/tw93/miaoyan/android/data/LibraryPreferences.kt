package com.tw93.miaoyan.android.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.libraryDataStore by preferencesDataStore(name = "miaoyan_library")

class LibraryPreferences(private val context: Context) {
    val rootUri: Flow<Uri?> = context.libraryDataStore.data.map { preferences ->
        preferences[RootUriKey]?.let(Uri::parse)
    }

    suspend fun setRoot(uri: Uri) {
        context.libraryDataStore.edit { preferences ->
            preferences[RootUriKey] = uri.toString()
        }
    }

    private companion object {
        val RootUriKey = stringPreferencesKey("root_tree_uri")
    }
}
