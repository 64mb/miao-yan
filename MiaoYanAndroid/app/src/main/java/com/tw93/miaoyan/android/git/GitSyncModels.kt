package com.tw93.miaoyan.android.git

data class GitSyncConfig(
    val repositoryUrl: String,
    val authorName: String,
    val authorEmail: String,
    val periodicEnabled: Boolean,
) {
    fun validated(): GitSyncConfig {
        val normalizedUrl = GitOrigin.normalizeHttpsRepositoryUrl(repositoryUrl)
        if (authorName.isBlank() || authorName.hasCredentialControls()) {
            throw GitSyncException.Configuration("Enter a valid Git author name.")
        }
        if (authorEmail.isBlank() || !authorEmail.contains('@')) {
            throw GitSyncException.Configuration("Enter a valid Git author email.")
        }
        if (authorEmail.hasCredentialControls()) throw GitSyncException.Configuration("Enter a valid Git author email.")
        return copy(
            repositoryUrl = normalizedUrl,
            authorName = authorName.trim(),
            authorEmail = authorEmail.trim(),
        )
    }
}

data class GitCredentials(val username: String, val personalAccessToken: String) {
    fun validated(): GitCredentials {
        if (username.isBlank() || username.hasCredentialControls() || username.length > 1_024) {
            throw GitSyncException.Configuration("Enter a valid HTTPS username.")
        }
        if (
            personalAccessToken.isBlank() || personalAccessToken.hasCredentialControls() ||
            personalAccessToken.length > 16_384
        ) {
            throw GitSyncException.Configuration("Enter a personal access token.")
        }
        return copy(username = username.trim())
    }
}

internal object GitSyncSetupPolicy {
    fun canSync(config: GitSyncConfig?, credentials: GitCredentials?): Boolean =
        config != null && credentials != null && runCatching {
            config.validated()
            credentials.validated()
        }.isSuccess
}

enum class GitSyncAttemptOutcome { None, Success, Failed }

data class GitSyncStatus(
    val lastSuccessAtMillis: Long? = null,
    val lastAttemptAtMillis: Long? = null,
    val lastAttemptOutcome: GitSyncAttemptOutcome = GitSyncAttemptOutcome.None,
    val hasLocalChanges: Boolean = false,
) {
    fun afterLocalChange(): GitSyncStatus = copy(hasLocalChanges = true)

    fun afterAttempt(succeeded: Boolean, atMillis: Long): GitSyncStatus = copy(
        lastSuccessAtMillis = if (succeeded) atMillis else lastSuccessAtMillis,
        lastAttemptAtMillis = atMillis,
        lastAttemptOutcome = if (succeeded) GitSyncAttemptOutcome.Success else GitSyncAttemptOutcome.Failed,
        hasLocalChanges = if (succeeded) false else hasLocalChanges,
    )
}

private fun String.hasCredentialControls(): Boolean = any { it == '\r' || it == '\n' || it == '\u0000' }

data class GitSyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val deleted: Int,
    val commitId: String,
)

enum class GitConflictChoice { Local, Remote }

data class GitConflictFile(
    val path: String,
    val localModifiedAtMillis: Long?,
    val remoteModifiedAtMillis: Long?,
    val localExists: Boolean,
    val remoteExists: Boolean,
)

data class GitConflictDetails(
    val localCommit: String,
    val remoteCommit: String,
    val files: List<GitConflictFile>,
)

sealed class GitSyncException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Configuration(message: String) : GitSyncException(message)
    class Conflict(
        val details: GitConflictDetails? = null,
        message: String = "Choose Local or Remote for the conflicting files.",
    ) : GitSyncException(message)
    class Limit(message: String) : GitSyncException(message)
    class Remote(message: String, cause: Throwable? = null) : GitSyncException(message, cause)
    class Storage(message: String, cause: Throwable? = null) : GitSyncException(message, cause)
}

internal object GitSyncLimits {
    const val MaxAttachmentBytes: Long = 25L * 1024L * 1024L
    const val MaxFiles = 10_000
    const val MaxScannedEntries = 20_000
}
