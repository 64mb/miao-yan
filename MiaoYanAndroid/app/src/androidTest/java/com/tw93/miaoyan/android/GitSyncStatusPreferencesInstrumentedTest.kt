package com.tw93.miaoyan.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tw93.miaoyan.android.git.GitSyncAttemptOutcome
import com.tw93.miaoyan.android.git.GitSyncConfig
import com.tw93.miaoyan.android.git.GitSyncPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitSyncStatusPreferencesInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = GitSyncPreferences(context)

    @Before
    fun clearBefore() = runBlocking { preferences.clear() }

    @After
    fun clearAfter() = runBlocking { preferences.clear() }

    @Test
    fun persistsFailureDirtyAndSuccessfulCleanStatusWithoutCredentials() = runBlocking {
        preferences.save(config("https://example.com/notes.git"))
        preferences.markLocalChanges()
        preferences.recordSyncAttempt(succeeded = false, atMillis = 100L)
        val failed = preferences.syncStatus.first()

        assertTrue(failed.hasLocalChanges)
        assertEquals(GitSyncAttemptOutcome.Failed, failed.lastAttemptOutcome)
        assertEquals(100L, failed.lastAttemptAtMillis)
        assertNull(failed.lastSuccessAtMillis)

        preferences.recordSyncAttempt(succeeded = true, atMillis = 200L)
        val succeeded = preferences.syncStatus.first()
        assertFalse(succeeded.hasLocalChanges)
        assertEquals(GitSyncAttemptOutcome.Success, succeeded.lastAttemptOutcome)
        assertEquals(200L, succeeded.lastAttemptAtMillis)
        assertEquals(200L, succeeded.lastSuccessAtMillis)
    }

    @Test
    fun changingRepositoryClearsPriorBackupEvidence() = runBlocking {
        preferences.save(config("https://example.com/one.git"))
        preferences.recordSyncAttempt(succeeded = true, atMillis = 200L)
        preferences.save(config("https://example.com/two.git"))

        val status = preferences.syncStatus.first()
        assertNull(status.lastSuccessAtMillis)
        assertNull(status.lastAttemptAtMillis)
        assertEquals(GitSyncAttemptOutcome.None, status.lastAttemptOutcome)
        assertFalse(status.hasLocalChanges)
    }

    private fun config(url: String) = GitSyncConfig(
        repositoryUrl = url,
        authorName = "Miao Yan",
        authorEmail = "miao@example.com",
        periodicEnabled = false,
    )
}
