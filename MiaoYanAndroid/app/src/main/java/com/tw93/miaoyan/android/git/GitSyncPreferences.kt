package com.tw93.miaoyan.android.git

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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

    val syncStatus: Flow<GitSyncStatus> = context.gitSyncDataStore.data.map { values ->
        GitSyncStatus(
            lastSuccessAtMillis = values[LastSuccessAtKey],
            lastAttemptAtMillis = values[LastAttemptAtKey],
            lastAttemptOutcome = values[LastAttemptOutcomeKey]
                ?.let { stored -> runCatching { GitSyncAttemptOutcome.valueOf(stored) }.getOrNull() }
                ?: GitSyncAttemptOutcome.None,
            hasLocalChanges = values[HasLocalChangesKey] ?: false,
        )
    }

    suspend fun save(config: GitSyncConfig) {
        val validated = config.validated()
        context.gitSyncDataStore.edit { values ->
            val repositoryChanged = values[RepositoryUrlKey] != validated.repositoryUrl
            values[RepositoryUrlKey] = validated.repositoryUrl
            values[AuthorNameKey] = validated.authorName
            values[AuthorEmailKey] = validated.authorEmail
            values[PeriodicEnabledKey] = validated.periodicEnabled
            if (repositoryChanged) clearSyncStatus(values)
        }
    }

    suspend fun markLocalChanges() {
        context.gitSyncDataStore.edit { values -> values[HasLocalChangesKey] = true }
    }

    suspend fun recordSyncAttempt(succeeded: Boolean, atMillis: Long) {
        context.gitSyncDataStore.edit { values ->
            values[LastAttemptAtKey] = atMillis
            values[LastAttemptOutcomeKey] = if (succeeded) {
                GitSyncAttemptOutcome.Success.name
            } else {
                GitSyncAttemptOutcome.Failed.name
            }
            if (succeeded) {
                values[LastSuccessAtKey] = atMillis
                values[HasLocalChangesKey] = false
            }
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

    private fun clearSyncStatus(values: androidx.datastore.preferences.core.MutablePreferences) {
        values.remove(LastSuccessAtKey)
        values.remove(LastAttemptAtKey)
        values.remove(LastAttemptOutcomeKey)
        values.remove(HasLocalChangesKey)
    }

    private companion object {
        val RepositoryUrlKey = stringPreferencesKey("repository_url")
        val AuthorNameKey = stringPreferencesKey("author_name")
        val AuthorEmailKey = stringPreferencesKey("author_email")
        val PeriodicEnabledKey = booleanPreferencesKey("periodic_enabled")
        val PendingConflictKey = stringPreferencesKey("pending_conflict")
        val LastSuccessAtKey = longPreferencesKey("last_success_at_millis")
        val LastAttemptAtKey = longPreferencesKey("last_attempt_at_millis")
        val LastAttemptOutcomeKey = stringPreferencesKey("last_attempt_outcome")
        val HasLocalChangesKey = booleanPreferencesKey("has_local_changes")
    }
}
