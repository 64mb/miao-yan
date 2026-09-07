package com.tw93.miaoyan.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tw93.miaoyan.android.data.LocalLibraryRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalLibraryRepositoryTest {
    private val baseContext: Context = ApplicationProvider.getApplicationContext()
    private val sandbox = File(baseContext.cacheDir, "local-library-repository-test")
    private val context: Context = TestContext(baseContext, sandbox)
    private val root: File
        get() = File(context.filesDir, "libraries/default")

    @Before
    fun resetLibrary() {
        sandbox.deleteRecursively()
    }

    @After
    fun cleanLibrary() {
        sandbox.deleteRecursively()
    }

    @Test
    fun scansNestedNotesAndExcludesServiceAttachmentAndSymlinkTrees() = runBlocking {
        write("Projects/Nested/Visible.md", "visible")
        write(".git/hidden.md", "git")
        write(".Trash/deleted.md", "trash")
        write("Trash/deleted.md", "trash")
        write("i/caption.md", "image metadata")
        write("files/readme.md", "attachment metadata")
        val outside = File(sandbox, "outside").apply { mkdirs() }
        File(outside, "escaped.md").writeText("escaped")
        root.mkdirs()
        Files.createSymbolicLink(File(root, "Linked").toPath(), outside.toPath())

        val paths = LocalLibraryRepository(context).scan().map { it.relativePath }

        assertEquals(listOf("Projects/Nested/Visible.md"), paths)
    }

    @Test
    fun createSaveRenameTrashAndRestoreStayCanonicalAndRecoverable() = runBlocking {
        val repository = LocalLibraryRepository(context)
        val created = repository.createRootNote("Draft")
        val saved = repository.save(created, "See [[Target]]")
        assertEquals("See [[Target]]", File(root, "Draft.md").readText())
        assertTrue(saved.contentHash.isNotBlank())

        val renamed = repository.rename(saved.note, "Final.md")
        assertEquals("Final.md", renamed.relativePath)
        assertFalse(File(root, "Draft.md").exists())

        repository.moveToTrash(renamed)
        assertFalse(File(root, "Final.md").exists())
        val trashed = repository.listTrash().single()
        assertEquals("Final.md", trashed.originalRelativePath)

        val restored = repository.restore(trashed)
        assertFalse(restored.restoredToRoot)
        assertEquals("See [[Target]]", File(root, "Final.md").readText())
        assertTrue(repository.listTrash().isEmpty())
    }

    @Test
    fun saveFailsClosedAfterExternalChangeAndRenameRejectsNormalizedCollision() = runBlocking {
        val repository = LocalLibraryRepository(context)
        val first = repository.createRootNote("Café.md")
        val second = repository.createRootNote("Other.md")
        File(root, first.note.relativePath).writeText("external")

        val saveFailure = runCatching { repository.save(first, "overwrite") }.exceptionOrNull()
        assertTrue(saveFailure is IllegalStateException)
        assertEquals("external", File(root, first.note.relativePath).readText())

        val renameFailure = runCatching {
            repository.rename(second.note, "CAFE\u0301.MD")
        }.exceptionOrNull()
        assertTrue(renameFailure is IllegalStateException)
        assertTrue(File(root, second.note.relativePath).isFile)
    }

    private fun write(relativePath: String, content: String) {
        File(root, relativePath).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    private class TestContext(base: Context, private val sandbox: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(sandbox, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(sandbox, "cache").apply { mkdirs() }
    }
}
