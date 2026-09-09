package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.git.GitIndexDiff
import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitIndexDiffTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unbornMainWithStagedLibraryCreatesInitialDiffInsteadOfReadingMissingHead() {
        val directory = temporaryFolder.newFolder("unborn-with-note")
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            assertNull(git.repository.resolve("HEAD"))
            File(directory, "Welcome.md").writeText("# Welcome")
            git.add().addFilepattern("Welcome.md").call()

            val changes = GitIndexDiff.stagedChanges(git, git.repository)

            assertEquals(listOf("Welcome.md"), changes.map(DiffEntry::getNewPath))
            assertEquals(listOf(DiffEntry.ChangeType.ADD), changes.map(DiffEntry::getChangeType))
            git.commit()
                .setMessage("initial")
                .setAuthor("MiaoYan", "android@example.com")
                .setCommitter("MiaoYan", "android@example.com")
                .call()
        }
    }

    @Test
    fun emptyUnbornMainHasNoStagedChanges() {
        val directory = temporaryFolder.newFolder("empty-unborn")
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            assertNull(git.repository.resolve("HEAD"))
            assertEquals(emptyList<DiffEntry>(), GitIndexDiff.stagedChanges(git, git.repository))
        }
    }

    @Test
    fun existingMainStillDiffsStagedChangesAgainstHead() {
        val directory = temporaryFolder.newFolder("existing-main")
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            val note = File(directory, "Welcome.md")
            note.writeText("first")
            git.add().addFilepattern("Welcome.md").call()
            git.commit()
                .setMessage("initial")
                .setAuthor("MiaoYan", "android@example.com")
                .setCommitter("MiaoYan", "android@example.com")
                .call()

            note.writeText("second")
            git.add().addFilepattern("Welcome.md").call()
            val changes = GitIndexDiff.stagedChanges(git, git.repository)

            assertEquals(listOf("Welcome.md"), changes.map(DiffEntry::getNewPath))
            assertEquals(listOf(DiffEntry.ChangeType.MODIFY), changes.map(DiffEntry::getChangeType))
        }
    }

    @Test
    fun initialCommitProducedFromUnbornMainCanPopulateAnEmptyRemoteMain() {
        val remoteDirectory = temporaryFolder.newFolder("empty-remote")
        Git.init().setDirectory(remoteDirectory).setBare(true).call().close()
        val directory = temporaryFolder.newFolder("initial-push")
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            File(directory, "Welcome.md").writeText("# Welcome")
            git.add().addFilepattern("Welcome.md").call()
            assertEquals(1, GitIndexDiff.stagedChanges(git, git.repository).size)
            val initial = git.commit()
                .setMessage("initial")
                .setAuthor("MiaoYan", "android@example.com")
                .setCommitter("MiaoYan", "android@example.com")
                .call()
            git.push()
                .setRemote(remoteDirectory.toURI().toString())
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()

            Git.open(remoteDirectory).use { remote ->
                assertEquals(initial.id, remote.repository.resolve("refs/heads/main"))
            }
        }
    }

    @Test
    fun emptyUnbornLocalCanFetchAnExistingRemoteMain() {
        val seedDirectory = temporaryFolder.newFolder("remote-seed")
        val remoteDirectory = temporaryFolder.newFolder("existing-remote")
        Git.init().setDirectory(remoteDirectory).setBare(true).call().close()
        val expected = Git.init().setDirectory(seedDirectory).setInitialBranch("main").call().use { seed ->
            File(seedDirectory, "Remote.md").writeText("# Remote")
            seed.add().addFilepattern("Remote.md").call()
            seed.commit()
                .setMessage("remote initial")
                .setAuthor("MiaoYan", "android@example.com")
                .setCommitter("MiaoYan", "android@example.com")
                .call()
                .also {
                    seed.push()
                        .setRemote(remoteDirectory.toURI().toString())
                        .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                        .call()
                }
                .id
        }
        val localDirectory = temporaryFolder.newFolder("initial-fetch")
        Git.init().setDirectory(localDirectory).setInitialBranch("main").call().use { local ->
            assertEquals(emptyList<DiffEntry>(), GitIndexDiff.stagedChanges(local, local.repository))
            local.fetch()
                .setRemote(remoteDirectory.toURI().toString())
                .setRefSpecs(RefSpec("+refs/heads/main:refs/remotes/origin/main"))
                .call()

            assertEquals(expected, local.repository.resolve("refs/remotes/origin/main"))
        }
    }

    @Test
    fun emojiFolderRenameCanSyncAgainAfterRepositoryReopen() {
        val remoteDirectory = temporaryFolder.newFolder("emoji-remote")
        Git.init().setDirectory(remoteDirectory).setBare(true).call().close()
        val directory = temporaryFolder.newFolder("emoji-local")
        val remoteUrl = remoteDirectory.toURI().toString()

        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            File(directory, "Ideas").mkdirs()
            File(directory, "Ideas/Plan.md").writeText("# Plan")
            git.add().addFilepattern("Ideas/Plan.md").call()
            git.commit()
                .setMessage("initial")
                .setAuthor("MiaoYan", "android@example.com")
                .setCommitter("MiaoYan", "android@example.com")
                .call()
            git.push()
                .setRemote(remoteUrl)
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()

            File(directory, "Ideas").renameTo(File(directory, "🔮 Ideas"))
            git.add().addFilepattern("🔮 Ideas/Plan.md").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            val changes = GitIndexDiff.stagedChanges(git, git.repository)
            assertEquals(
                setOf("Ideas/Plan.md", "🔮 Ideas/Plan.md"),
                changes.flatMap { listOf(it.oldPath, it.newPath) }
                    .filterNot { it == DiffEntry.DEV_NULL }
                    .toSet(),
            )
            git.commit()
                .setMessage("Sync from Android")
                .setAuthor("MiaoYan", "android@example.com")
                .setCommitter("MiaoYan", "android@example.com")
                .call()
            git.push()
                .setRemote(remoteUrl)
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()
        }

        FileRepositoryBuilder()
            .setGitDir(File(directory, ".git"))
            .setWorkTree(directory)
            .build()
            .use { reopened ->
                Git(reopened).use { git ->
                    git.fetch()
                        .setRemote(remoteUrl)
                        .setRefSpecs(RefSpec("+refs/heads/main:refs/remotes/origin/main"))
                        .call()

                    assertEquals(reopened.resolve(Constants.HEAD), reopened.resolve("refs/remotes/origin/main"))
                    assertTrue(git.status().call().isClean)
                    assertTrue(File(directory, "🔮 Ideas/Plan.md").isFile)
                }
            }
    }
}
