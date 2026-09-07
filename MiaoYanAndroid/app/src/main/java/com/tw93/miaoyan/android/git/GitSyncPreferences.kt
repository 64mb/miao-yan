package com.tw93.miaoyan.android.git

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.gitSyncDataStore by preferencesDataStore(name = "miaoyan_git_sync")

class GitSyncPreferences(private val context: Context) {
    val config: Flow<GitSyncConfig?> = context.gitSyncDataStore.data.map { values ->
        val url = values[RepositoryUrlKey] ?: return@map null
        val name = values[AuthorNameKey] ?: return@map null
        val email = values[AuthorEmailKey] ?: return@map null
        GitSyncConfig(
            repositoryUrl = url,
            authorName = name,
            authorEmail = email,
            periodicEnabled = values[PeriodicEnabledKey] ?: false,
        )
    }

    val pendingConflict: Flow<GitConflictDetails?> = context.gitSyncDataStore.data.map { values ->
        values[PendingConflictKey]?.let(GitConflictCodec::decode)
    }

    suspend fun save(config: GitSyncConfig) {
        val validated = config.validated()
        context.gitSyncDataStore.edit { values ->
            values[RepositoryUrlKey] = validated.repositoryUrl
            values[AuthorNameKey] = validated.authorName
            values[AuthorEmailKey] = validated.authorEmail
            values[PeriodicEnabledKey] = validated.periodicEnabled
        }
    }

    suspend fun clear() {
        context.gitSyncDataStore.edit { it.clear() }
    }

    suspend fun setPendingConflict(details: GitConflictDetails?) {
        context.gitSyncDataStore.edit { values ->
            if (details == null) values.remove(PendingConflictKey)
            else values[PendingConflictKey] = GitConflictCodec.encode(details)
        }
    }

    private companion object {
        val RepositoryUrlKey = stringPreferencesKey("repository_url")
        val AuthorNameKey = stringPreferencesKey("author_name")
        val AuthorEmailKey = stringPreferencesKey("author_email")
        val PeriodicEnabledKey = booleanPreferencesKey("periodic_enabled")
        val PendingConflictKey = stringPreferencesKey("pending_conflict")
    }
}
