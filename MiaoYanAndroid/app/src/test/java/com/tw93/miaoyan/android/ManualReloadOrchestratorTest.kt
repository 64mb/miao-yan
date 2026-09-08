package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.git.GitCredentials
import com.tw93.miaoyan.android.git.GitSyncConfig
import com.tw93.miaoyan.android.git.GitSyncSetupPolicy
import com.tw93.miaoyan.android.ui.ManualReloadOrchestrator
import com.tw93.miaoyan.android.ui.ManualReloadRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualReloadOrchestratorTest {
    @Test
    fun routesOnlyValidConfigurationAndCredentialsToGit() {
        val config = validConfig()
        val credentials = GitCredentials("miao", "token")

        assertTrue(GitSyncSetupPolicy.canSync(config, credentials))
        assertFalse(GitSyncSetupPolicy.canSync(null, credentials))
        assertFalse(GitSyncSetupPolicy.canSync(config, null))
        assertFalse(GitSyncSetupPolicy.canSync(config.copy(repositoryUrl = "ssh://example.com/notes"), credentials))
        assertFalse(GitSyncSetupPolicy.canSync(config, credentials.copy(personalAccessToken = "")))
    }

    @Test
    fun localReloadOnlyRefreshesLibrary() = runBlocking {
        val calls = mutableListOf<String>()
        orchestrator(calls).run(ManualReloadRoute.Local, hasDirtyDraft = true)

        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun cleanGitReloadSyncsThenRefreshes() = runBlocking {
        val calls = mutableListOf<String>()
        orchestrator(calls).run(ManualReloadRoute.Git, hasDirtyDraft = false)

        assertEquals(listOf("sync", "refresh"), calls)
    }

    @Test
    fun dirtyGitReloadSavesOwnerBeforeSyncAndRefresh() = runBlocking {
        val calls = mutableListOf<String>()
        orchestrator(calls).run(ManualReloadRoute.Git, hasDirtyDraft = true)

        assertEquals(listOf("save", "sync", "refresh"), calls)
    }

    @Test
    fun saveFailureLeavesSyncAndRefreshUntouched() = runBlocking {
        val calls = mutableListOf<String>()
        val saveFailure = IllegalStateException("save failed")
        val result = runCatching {
            orchestrator(calls, saveFailure = saveFailure).run(ManualReloadRoute.Git, hasDirtyDraft = true)
        }

        assertSame(saveFailure, result.exceptionOrNull())
        assertEquals(listOf("save"), calls)
    }

    @Test
    fun syncFailureStillRefreshesAndRemainsTheReportedFailure() = runBlocking {
        val calls = mutableListOf<String>()
        val syncFailure = IllegalStateException("sync failed")
        val result = runCatching {
            orchestrator(calls, syncFailure = syncFailure).run(ManualReloadRoute.Git, hasDirtyDraft = false)
        }

        assertSame(syncFailure, result.exceptionOrNull())
        assertEquals(listOf("sync", "refresh"), calls)
    }

    @Test
    fun refreshFailureIsReportedAfterSuccessfulSync() = runBlocking {
        val calls = mutableListOf<String>()
        val refreshFailure = IllegalStateException("refresh failed")
        val result = runCatching {
            orchestrator(calls, refreshFailure = refreshFailure)
                .run(ManualReloadRoute.Git, hasDirtyDraft = false)
        }

        assertSame(refreshFailure, result.exceptionOrNull())
        assertEquals(listOf("sync", "refresh"), calls)
    }

    private fun orchestrator(
        calls: MutableList<String>,
        saveFailure: Throwable? = null,
        syncFailure: Throwable? = null,
        refreshFailure: Throwable? = null,
    ) = ManualReloadOrchestrator(
        saveDirtyDraft = {
            calls += "save"
            saveFailure?.let { throw it }
        },
        syncGit = {
            calls += "sync"
            syncFailure?.let { throw it }
        },
        refreshLibrary = {
            calls += "refresh"
            refreshFailure?.let { throw it }
        },
    )

    private fun validConfig() = GitSyncConfig(
        repositoryUrl = "https://example.com/notes.git",
        authorName = "Miao Yan",
        authorEmail = "miao@example.com",
        periodicEnabled = false,
    )
}
