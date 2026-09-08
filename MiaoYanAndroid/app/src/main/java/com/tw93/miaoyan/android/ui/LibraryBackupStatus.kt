package com.tw93.miaoyan.android.ui

import com.tw93.miaoyan.android.git.GitSyncAttemptOutcome
import com.tw93.miaoyan.android.git.GitSyncStatus

internal enum class LibraryBackupStatusKind {
    Unconfigured,
    FirstSyncRequired,
    Synced,
    LocallyModified,
    Failed,
}

internal data class LibraryBackupStatus(
    val kind: LibraryBackupStatusKind,
    val lastSuccessAtMillis: Long? = null,
)

internal object LibraryBackupStatusPolicy {
    fun evaluate(gitConfigured: Boolean, status: GitSyncStatus): LibraryBackupStatus = when {
        !gitConfigured -> LibraryBackupStatus(LibraryBackupStatusKind.Unconfigured)
        status.lastAttemptOutcome == GitSyncAttemptOutcome.Failed -> LibraryBackupStatus(
            LibraryBackupStatusKind.Failed,
            status.lastSuccessAtMillis,
        )
        status.lastSuccessAtMillis == null -> LibraryBackupStatus(LibraryBackupStatusKind.FirstSyncRequired)
        status.hasLocalChanges -> LibraryBackupStatus(
            LibraryBackupStatusKind.LocallyModified,
            status.lastSuccessAtMillis,
        )
        else -> LibraryBackupStatus(LibraryBackupStatusKind.Synced, status.lastSuccessAtMillis)
    }
}
