package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.git.GitConflictChoice
import com.tw93.miaoyan.android.git.GitConflictResolver
import com.tw93.miaoyan.android.git.GitSyncConfig
import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitConflictResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsWholeFileResolutionWithoutContentMerge() {
        val directory = temporaryFolder.newFolder("conflict-resolution")
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            val base = commit(git, directory, mapOf("note.md" to "base"), "base")
            val remote = commit(
                git,
                directory,
                mapOf("note.md" to "remote complete version", "remote.md" to "from remote"),
                "remote",
            )
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(base.name).call()
            val local = commit(
                git,
                directory,
                mapOf("note.md" to "local complete version", "local.md" to "from local"),
                "local",
            )

            val localFileTimes = mapOf("note.md" to 123_456L, "local.md" to 234_567L)
            val details = GitConflictResolver.describe(git.repository, local, remote, localFileTimes)
            assertEquals(listOf("local.md", "note.md", "remote.md"), details.files.map { it.path })
            assertEquals(123_456L, details.files.single { it.path == "note.md" }.localModifiedAtMillis)
            assertEquals(null, details.files.single { it.path == "remote.md" }.localModifiedAtMillis)
            assertTrue(details.files.single { it.path == "note.md" }.remoteModifiedAtMillis != null)
            val choices = mapOf(
                "local.md" to GitConflictChoice.Local,
                "note.md" to GitConflictChoice.Remote,
                "remote.md" to GitConflictChoice.Remote,
            )
            val resolution = GitConflictResolver.createResolutionCommit(
                git.repository,
                details,
                choices,
                GitSyncConfig("https://example.com/notes.git", "MiaoYan", "android@example.com", false),
            )
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(resolution.name).call()

            assertEquals("remote complete version", File(directory, "note.md").readText())
            assertEquals("from local", File(directory, "local.md").readText())
            assertEquals("from remote", File(directory, "remote.md").readText())
            assertFalse(File(directory, "note.md").readText().contains("local complete version"))
            RevWalk(git.repository).use { walk ->
                assertEquals(2, walk.parseCommit(resolution).parentCount)
            }
        }
    }

    private fun commit(
        git: Git,
        directory: File,
        files: Map<String, String>,
        message: String,
    ): ObjectId {
        files.forEach { (path, text) -> File(directory, path).writeText(text) }
        git.add().addFilepattern(".").call()
        return git.commit()
            .setMessage(message)
            .setAuthor("MiaoYan", "android@example.com")
            .setCommitter("MiaoYan", "android@example.com")
            .call().id
    }
}
