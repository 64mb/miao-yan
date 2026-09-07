package com.tw93.miaoyan.android.git

import android.content.Context
import com.tw93.miaoyan.android.data.LibraryRepository
import com.tw93.miaoyan.android.data.LibraryRepositoryProvider
import com.tw93.miaoyan.android.data.core.LibraryAccess
import com.tw93.miaoyan.android.data.core.LibraryMutationGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class GitSyncCoordinator(
    context: Context,
    private val repository: LibraryRepository = LibraryRepositoryProvider.get(context),
    private val libraryAccess: LibraryAccess = LibraryMutationGate,
    private val publishRefreshEvents: Boolean = true,
) {
    private val preferences = GitSyncPreferences(context)
    private val credentialStore = KeystoreCredentialStore(context)
    private val workingTree = GitWorkingTreeSync(context)

    suspend fun sync(deadlineAfterMillis: Long? = null): GitSyncResult = withContext(Dispatchers.IO) {
        val config = preferences.config.first()
            ?: throw GitSyncException.Configuration("Configure Git sync first.")
        val credentials = credentialStore.load(config.repositoryUrl)
            ?: throw GitSyncException.Configuration(
                "Enter Git HTTPS credentials for this repository URL.",
            )
        runWithProjectionRefresh {
            workingTree.sync(config, credentials, deadlineNanos(deadlineAfterMillis))
        }
    }

    suspend fun resolve(
        details: GitConflictDetails,
        choices: Map<String, GitConflictChoice>,
        deadlineAfterMillis: Long? = null,
    ): GitSyncResult = withContext(Dispatchers.IO) {
        val config = preferences.config.first()
            ?: throw GitSyncException.Configuration("Configure Git sync first.")
        val credentials = credentialStore.load(config.repositoryUrl)
            ?: throw GitSyncException.Configuration(
                "Enter Git HTTPS credentials for this repository URL.",
            )
        runWithProjectionRefresh {
            workingTree.resolve(
                config,
                credentials,
                details,
                choices,
                deadlineNanos(deadlineAfterMillis),
            )
        }
    }

    private suspend fun runWithProjectionRefresh(
        operation: suspend () -> GitSyncResult,
    ): GitSyncResult {
        val attempt = runCatching {
            libraryAccess.withExclusiveAccess { operation() }
        }
        val conflict = attempt.exceptionOrNull() as? GitSyncException.Conflict
        val preferenceUpdate = runCatching {
            if (conflict?.details != null) {
                preferences.setPendingConflict(conflict.details)
            } else if (attempt.isSuccess) {
                preferences.setPendingConflict(null)
            }
        }
        val refresh = runCatching { repository.scan() }
        if (publishRefreshEvents) GitSyncRefreshEvents.publish()
        attempt.exceptionOrNull()?.let { throw it }
        preferenceUpdate.exceptionOrNull()?.let { error ->
            throw GitSyncException.Storage("Git sync state could not be saved.", error)
        }
        refresh.exceptionOrNull()?.let { error ->
            throw GitSyncException.Storage(
                "Git sync completed, but the library index could not be refreshed.",
                error,
            )
        }
        return attempt.getOrThrow()
    }

    private fun deadlineNanos(deadlineAfterMillis: Long?): Long? =
        deadlineAfterMillis?.let { System.nanoTime() + it * 1_000_000L }
}
