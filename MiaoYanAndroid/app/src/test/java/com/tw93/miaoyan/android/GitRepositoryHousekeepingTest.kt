package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.git.GitIndexDiff
import com.tw93.miaoyan.android.git.GitRepositoryHousekeeping
import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.JGitInternalException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitRepositoryHousekeepingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun staleIndexLockAfterRestartDoesNotBlockEmojiRenameCommit() {
        val directory = temporaryFolder.newFolder("library")
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            File(directory, "Ideas").mkdirs()
            File(directory, "Ideas/Plan.md").writeText("# Plan")
            git.add().addFilepattern("Ideas/Plan.md").call()
            git.commit()
                .setMessage("initial")
                .setAuthor("MiaoYan", "android@example.com")
                .setCommitter("MiaoYan", "android@example.com")
                .call()
        }

        assertTrue(File(directory, "Ideas").renameTo(File(directory, "🔮 Ideas")))
        val staleLock = File(directory, ".git/index.lock")
        staleLock.writeText("interrupted write")

        Git.open(directory).use { blocked ->
            assertThrows(JGitInternalException::class.java) {
                blocked.add().addFilepattern("🔮 Ideas/Plan.md").call()
            }
        }

        GitRepositoryHousekeeping.removeStaleLocks(File(directory, ".git"))

        Git.open(directory).use { reopened ->
            reopened.add().addFilepattern("🔮 Ideas/Plan.md").call()
            reopened.add().setUpdate(true).addFilepattern(".").call()
            val changes = GitIndexDiff.stagedChanges(reopened, reopened.repository)
            assertEquals(2, changes.size)
            reopened.commit()
                .setMessage("Sync from Android")
                .setAuthor("MiaoYan", "android@example.com")
                .setCommitter("MiaoYan", "android@example.com")
                .call()
            assertTrue(reopened.status().call().isClean)
        }

        assertFalse(staleLock.exists())
        assertTrue(File(directory, "🔮 Ideas/Plan.md").isFile)
    }

    @Test
    fun removesOnlyGitLocksAndNeverFollowsSymlinks() {
        val directory = temporaryFolder.newFolder("metadata")
        val gitDirectory = File(directory, ".git").apply { mkdirs() }
        val refLock = File(gitDirectory, "refs/heads/main.lock").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }
        val config = File(gitDirectory, "config").apply { writeText("keep") }
        val outside = temporaryFolder.newFile("outside.lock").apply { writeText("keep") }
        val link = File(gitDirectory, "refs/external")
        Files.createSymbolicLink(link.toPath(), requireNotNull(outside.parentFile).toPath())

        GitRepositoryHousekeeping.removeStaleLocks(gitDirectory)

        assertFalse(refLock.exists())
        assertTrue(config.isFile)
        assertTrue(outside.isFile)
    }
}
