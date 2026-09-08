package com.tw93.miaoyan.android.ui

import kotlinx.coroutines.CancellationException

internal enum class ManualReloadRoute { Local, Git }

/** Keeps save/sync/refresh ordering testable without putting UI lifetime into Git code. */
internal class ManualReloadOrchestrator(
    private val saveDirtyDraft: suspend () -> Unit,
    private val syncGit: suspend () -> Unit,
    private val refreshLibrary: suspend () -> Unit,
) {
    suspend fun run(route: ManualReloadRoute, hasDirtyDraft: Boolean) {
        if (route == ManualReloadRoute.Local) {
            refreshLibrary()
            return
        }

        if (hasDirtyDraft) saveDirtyDraft()
        val syncFailure = captureFailure { syncGit() }
        val refreshFailure = captureFailure { refreshLibrary() }
        (syncFailure ?: refreshFailure)?.let { throw it }
    }

    private suspend fun captureFailure(operation: suspend () -> Unit): Throwable? = try {
        operation()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        error
    }
}
