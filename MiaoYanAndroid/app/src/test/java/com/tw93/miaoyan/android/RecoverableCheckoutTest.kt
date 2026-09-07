package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.git.RecoverableCheckout
import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoverableCheckoutTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun nextRunRestoresOldCommitAfterPostCheckoutValidationFailure() {
        val directory = temporaryFolder.newFolder("recoverable-checkout")
        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            val old = commit(git, directory, "old content", "old")
            val incoming = commit(git, directory, "incoming content", "incoming")
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(old.name).call()
            val checkout = RecoverableCheckout()

            assertThrows(IllegalStateException::class.java) {
                checkout.apply(git, incoming) { error("post-checkout validation failed") }
            }
            assertEquals(incoming, git.repository.resolve(Constants.HEAD))
            assertEquals("incoming content", File(directory, "note.md").readText())
            assertEquals(old, git.repository.resolve("refs/miaoyan/checkout-recovery"))

            var targetWasValidated = false
            assertTrue(
                checkout.recoverIfNeeded(
                    git = git,
                    validateTarget = { target -> targetWasValidated = target == old },
                    validateWorkingTree = { assertEquals("old content", File(directory, "note.md").readText()) },
                ),
            )
            assertTrue(targetWasValidated)
            assertEquals(old, git.repository.resolve(Constants.HEAD))
            assertEquals("old content", File(directory, "note.md").readText())
            assertNull(git.repository.resolve("refs/miaoyan/checkout-recovery"))
        }
    }

    private fun commit(git: Git, directory: File, text: String, message: String): ObjectId {
        File(directory, "note.md").writeText(text)
        git.add().addFilepattern("note.md").call()
        return git.commit()
            .setMessage(message)
            .setAuthor("MiaoYan", "android@example.com")
            .setCommitter("MiaoYan", "android@example.com")
            .call().id
    }
}
