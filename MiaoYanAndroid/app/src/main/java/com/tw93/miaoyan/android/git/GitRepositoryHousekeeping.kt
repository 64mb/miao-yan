package com.tw93.miaoyan.android.git

import java.io.File
import java.nio.file.Files

/**
 * Removes lock files left behind when Android terminates the app during a Git write.
 *
 * The private repository is only accessed while the library mutation gate is held, and MiaoYan does
 * not expose it to another process. A lock found before opening JGit is therefore stale. Removing
 * only lock files preserves notes, refs, commits, the index, and recoverable checkout snapshots.
 */
internal object GitRepositoryHousekeeping {
    private const val MaxLockDepth = 8

    fun removeStaleLocks(gitDirectory: File) {
        if (!gitDirectory.isDirectory) return

        removeLocksDirectlyInside(gitDirectory)
        removeLocksBelow(File(gitDirectory, "refs"))
        removeLocksBelow(File(gitDirectory, "logs"))
        removeLocksDirectlyInside(File(gitDirectory, "objects/pack"))
    }

    private fun removeLocksBelow(directory: File, depth: Int = 0) {
        if (depth > MaxLockDepth || !directory.isDirectory || Files.isSymbolicLink(directory.toPath())) {
            return
        }
        directory.listFiles().orEmpty().forEach { entry ->
            when {
                Files.isSymbolicLink(entry.toPath()) -> Unit
                entry.isDirectory -> removeLocksBelow(entry, depth + 1)
                entry.isFile && entry.name.endsWith(".lock") -> remove(entry)
            }
        }
    }

    private fun removeLocksDirectlyInside(directory: File) {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return
        directory.listFiles().orEmpty()
            .filter { entry ->
                !Files.isSymbolicLink(entry.toPath()) &&
                    entry.isFile &&
                    entry.name.endsWith(".lock")
            }
            .forEach(::remove)
    }

    private fun remove(lockFile: File) {
        try {
            Files.deleteIfExists(lockFile.toPath())
        } catch (error: Throwable) {
            GitSyncDiagnostics.rethrowIfFatal(error)
            throw GitSyncException.Storage(
                "Git sync could not clear stale local repository locks. Your local notes were kept unchanged.",
                error,
            )
        }
    }
}
