package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.git.GitHistoryPolicy
import com.tw93.miaoyan.android.git.GitHistoryRelation
import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.ObjectId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHistoryPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun identifiesAheadAndDivergedHistoriesWithoutMerging() {
        val directory = temporaryFolder.newFolder("repository")
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            val base = commit(git, directory, "note.md", "base", "base")
            val remote = commit(git, directory, "remote.md", "remote", "remote")
            assertEquals(GitHistoryRelation.RemoteAhead, GitHistoryPolicy.relation(git.repository, base, remote))
            assertEquals(GitHistoryRelation.LocalAhead, GitHistoryPolicy.relation(git.repository, remote, base))

            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(base.name).call()
            val local = commit(git, directory, "local.md", "local", "local")
            assertEquals(GitHistoryRelation.Diverged, GitHistoryPolicy.relation(git.repository, local, remote))
        }
    }

    @Test
    fun identicalTreesAreSafeAcrossUnrelatedMetadataCommits() {
        val directory = temporaryFolder.newFolder("same-tree")
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            val first = commit(git, directory, "note.md", "same", "first")
            val second = git.commit().setMessage("metadata only").setAllowEmpty(true)
                .setAuthor("MiaoYan", "android@example.com")
                .setCommitter("MiaoYan", "android@example.com")
                .call().id
            assertEquals(GitHistoryRelation.SameTree, GitHistoryPolicy.relation(git.repository, first, second))
        }
    }

    private fun commit(git: Git, directory: File, path: String, text: String, message: String): ObjectId {
        File(directory, path).writeText(text)
        git.add().addFilepattern(path).call()
        return git.commit()
            .setMessage(message)
            .setAuthor("MiaoYan", "android@example.com")
            .setCommitter("MiaoYan", "android@example.com")
            .call().id
    }
}
