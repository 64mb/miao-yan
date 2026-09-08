package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.git.GitSyncAttemptOutcome
import com.tw93.miaoyan.android.git.GitSyncStatus
import com.tw93.miaoyan.android.ui.LibraryBackupStatusKind
import com.tw93.miaoyan.android.ui.LibraryBackupStatusPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupStatusPolicyTest {
    @Test
    fun unconfiguredStateNeverTreatsStoredHistoryAsBackup() {
        val status = GitSyncStatus(
            lastSuccessAtMillis = 42L,
            lastAttemptOutcome = GitSyncAttemptOutcome.Success,
        )

        assertEquals(
            LibraryBackupStatusKind.Unconfigured,
            LibraryBackupStatusPolicy.evaluate(gitConfigured = false, status).kind,
        )
    }

    @Test
    fun configuredStateWithoutSuccessfulSyncRequiresFirstSync() {
        assertEquals(
            LibraryBackupStatusKind.FirstSyncRequired,
            LibraryBackupStatusPolicy.evaluate(gitConfigured = true, GitSyncStatus()).kind,
        )
    }

    @Test
    fun successfulCleanSyncCarriesItsTimestamp() {
        val result = LibraryBackupStatusPolicy.evaluate(
            gitConfigured = true,
            GitSyncStatus(
                lastSuccessAtMillis = 1_234L,
                lastAttemptOutcome = GitSyncAttemptOutcome.Success,
            ),
        )

        assertEquals(LibraryBackupStatusKind.Synced, result.kind)
        assertEquals(1_234L, result.lastSuccessAtMillis)
    }

    @Test
    fun localChangeAfterSuccessMakesBackupOutOfDate() {
        val result = LibraryBackupStatusPolicy.evaluate(
            gitConfigured = true,
            GitSyncStatus(
                lastSuccessAtMillis = 1_234L,
                lastAttemptOutcome = GitSyncAttemptOutcome.Success,
                hasLocalChanges = true,
            ),
        )

        assertEquals(LibraryBackupStatusKind.LocallyModified, result.kind)
    }

    @Test
    fun failedAttemptTakesPriorityOverEarlierSuccessAndLocalChanges() {
        val result = LibraryBackupStatusPolicy.evaluate(
            gitConfigured = true,
            GitSyncStatus(
                lastSuccessAtMillis = 1_234L,
                lastAttemptOutcome = GitSyncAttemptOutcome.Failed,
                hasLocalChanges = true,
            ),
        )

        assertEquals(LibraryBackupStatusKind.Failed, result.kind)
    }

    @Test
    fun statusTransitionsPersistDirtyAcrossFailureAndClearItOnSuccess() {
        val dirty = GitSyncStatus(lastSuccessAtMillis = 10L).afterLocalChange()
        val failed = dirty.afterAttempt(succeeded = false, atMillis = 20L)
        val succeeded = failed.afterAttempt(succeeded = true, atMillis = 30L)

        assertTrue(dirty.hasLocalChanges)
        assertTrue(failed.hasLocalChanges)
        assertEquals(GitSyncAttemptOutcome.Failed, failed.lastAttemptOutcome)
        assertFalse(succeeded.hasLocalChanges)
        assertEquals(GitSyncAttemptOutcome.Success, succeeded.lastAttemptOutcome)
        assertEquals(30L, succeeded.lastSuccessAtMillis)
    }
}
