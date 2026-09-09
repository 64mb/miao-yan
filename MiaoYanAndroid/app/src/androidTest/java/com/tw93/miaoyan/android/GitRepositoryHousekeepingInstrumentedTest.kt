package com.tw93.miaoyan.android

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tw93.miaoyan.android.git.GitCredentials
import com.tw93.miaoyan.android.git.GitSyncConfig
import com.tw93.miaoyan.android.git.GitSyncException
import com.tw93.miaoyan.android.git.GitWorkingTreeSync
import java.io.File
import java.util.UUID
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitRepositoryHousekeepingInstrumentedTest {
    @Test
    fun workingTreeSyncClearsStaleLockBeforePreparingEmojiRename() {
        val application = ApplicationProvider.getApplicationContext<android.content.Context>()
        val filesDirectory = File(application.cacheDir, "git-lock-${UUID.randomUUID()}")
        val context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = filesDirectory
        }
        val library = File(filesDirectory, "libraries/default").apply { mkdirs() }
        try {
            Git.init().setDirectory(library).setInitialBranch("main").call().use { git ->
                File(library, "Ideas").mkdirs()
                File(library, "Ideas/Plan.md").writeText("# Plan")
                git.add().addFilepattern("Ideas/Plan.md").call()
                git.commit()
                    .setMessage("initial")
                    .setAuthor("MiaoYan", "android@example.com")
                    .setCommitter("MiaoYan", "android@example.com")
                    .call()
            }

            assertTrue(File(library, "Ideas").renameTo(File(library, "🔮 Ideas")))
            val staleLock = File(library, ".git/index.lock").apply {
                writeText("interrupted write")
            }

            val failure = assertThrows(GitSyncException.Remote::class.java) {
                GitWorkingTreeSync(context).sync(
                    GitSyncConfig(
                        repositoryUrl = "https://127.0.0.1:1/notes.git",
                        authorName = "MiaoYan",
                        authorEmail = "android@example.com",
                        periodicEnabled = false,
                    ),
                    GitCredentials("test", "test-token"),
                )
            }

            assertTrue(failure.message.orEmpty().contains("fetch"))
            assertFalse(staleLock.exists())
            assertTrue(File(library, "🔮 Ideas/Plan.md").isFile)
            Git.open(library).use { reopened ->
                assertTrue(reopened.status().call().isClean)
                assertTrue(reopened.log().call().first().fullMessage == "Sync from Android")
            }
        } finally {
            filesDirectory.deleteRecursively()
        }
    }
}
