package com.tw93.miaoyan.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tw93.miaoyan.android.data.LocalLibraryRepository
import com.tw93.miaoyan.android.data.TrashManifestCodec
import com.tw93.miaoyan.android.model.LibraryFolder
import com.tw93.miaoyan.android.model.LibraryItemKind
import com.tw93.miaoyan.android.model.TrashedNote
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
    fun listsFoldersAndCreatesNotesInTheCurrentNestedFolder() = runBlocking {
        val repository = LocalLibraryRepository(context)
        val projects = repository.createFolder("", "Projects")
        val nested = repository.createFolder(projects.relativePath, "2026")
        repository.createNote(nested.relativePath, "Plan")

        val rootListing = repository.listDirectory("")
        assertEquals(listOf("Projects"), rootListing.folders.map { it.displayName })
        assertTrue(rootListing.notes.isEmpty())

        val nestedListing = repository.listDirectory("Projects/2026")
        assertEquals("Projects/2026", nestedListing.currentFolder.relativePath)
        assertEquals(listOf("Projects/2026/Plan.md"), nestedListing.notes.map { it.relativePath })
        assertFalse(File(root, "Plan.md").exists())
    }

    @Test
    fun folderRenameIsAtomicAndRootCannotBeRenamedOrTrashed() = runBlocking {
        val repository = LocalLibraryRepository(context)
        val projects = repository.createFolder("", "Projects")
        repository.createNote(projects.relativePath, "Plan")

        val mutation = repository.renameFolder(projects, "Archive")

        assertEquals("Projects", mutation.oldRelativePath)
        assertEquals("Archive", mutation.newRelativePath)
        assertFalse(File(root, "Projects").exists())
        assertEquals("", File(root, "Archive/Plan.md").readText())
        val rootFolder = repository.listDirectory("").currentFolder
        assertTrue(runCatching { repository.renameFolder(rootFolder, "Other") }.isFailure)
        assertTrue(runCatching { repository.moveFolderToTrash(rootFolder) }.isFailure)
    }

    @Test
    fun folderTrashRestoreAndPermanentDeleteAreRecoverableAndContained() = runBlocking {
        val repository = LocalLibraryRepository(context)
        val folder = repository.createFolder("", "Projects")
        repository.createFolder(folder.relativePath, "Empty")
        repository.createNote(folder.relativePath, "Plan")

        repository.moveFolderToTrash(folder)
        assertFalse(File(root, "Projects").exists())
        val trashed = repository.listTrash().single()
        assertEquals(LibraryItemKind.FOLDER, trashed.kind)
        assertTrue(File(root, trashed.trashRelativePath + "/Empty").isDirectory)

        val restored = repository.restore(trashed)
        assertEquals("Projects", restored.restoredRelativePath)
        assertTrue(File(root, "Projects/Empty").isDirectory)

        repository.moveFolderToTrash(repository.listDirectory("").folders.single())
        val trashedAgain = repository.listTrash().single()
        repository.permanentlyDelete(trashedAgain)
        assertFalse(File(root, trashedAgain.trashRelativePath).exists())
        assertTrue(repository.listTrash().isEmpty())
    }

    @Test
    fun folderOperationsRejectTraversalReservedNamesAndSymlinkTrees() = runBlocking {
        val repository = LocalLibraryRepository(context)
        assertTrue(runCatching { repository.createFolder("", "../outside") }.isFailure)
        listOf(".git", ".Trash", "Trash", "i", "files").forEach { reserved ->
            assertTrue(runCatching { repository.createFolder("", reserved) }.isFailure)
        }

        root.mkdirs()
        val outside = File(sandbox, "outside-folder").apply { mkdirs() }
        File(outside, "Keep.md").writeText("keep")
        Files.createSymbolicLink(File(root, "Linked").toPath(), outside.toPath())
        val linked = LibraryFolder("Linked", "Linked", "Linked")

        assertTrue(runCatching { repository.listDirectory("Linked") }.isFailure)
        assertTrue(runCatching { repository.renameFolder(linked, "Renamed") }.isFailure)
        assertTrue(runCatching { repository.moveFolderToTrash(linked) }.isFailure)
        assertEquals("keep", File(outside, "Keep.md").readText())
    }

    @Test
    fun directoryListingRejectsExistingCaseOrUnicodeCollisions() = runBlocking {
        write("Projects/Café.md", "one")
        write("Projects/CAFE\u0301.MD", "two")

        assertTrue(runCatching { LocalLibraryRepository(context).listDirectory("Projects") }.isFailure)
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

    @Test
    fun permanentDeleteRemovesOnlyTheValidatedTrashNoteAndIsIdempotent() = runBlocking {
        val repository = LocalLibraryRepository(context)
        val note = repository.createRootNote("Erase.md")
        repository.moveToTrash(note.note)
        val trashed = repository.listTrash().single()
        val trashFile = File(root, trashed.trashRelativePath)

        repository.permanentlyDelete(trashed)

        assertFalse(trashFile.exists())
        assertTrue(repository.listTrash().isEmpty())
        repository.permanentlyDelete(trashed)
    }

    @Test
    fun permanentDeleteCleansAManifestForAnAlreadyMissingNote() = runBlocking {
        val repository = LocalLibraryRepository(context)
        val note = repository.createRootNote("Missing.md")
        repository.moveToTrash(note.note)
        val trashed = repository.listTrash().single()
        val manifest = File(root, ".Trash/manifest.v1")
        File(root, trashed.trashRelativePath).delete()

        repository.permanentlyDelete(trashed)

        assertFalse(manifest.readText().contains(requireNotNull(trashed.manifestId)))
        repository.permanentlyDelete(trashed)
    }

    @Test
    fun permanentDeleteRejectsTraversalAndSymlinksWithoutDeletingTheirTargets() = runBlocking {
        val repository = LocalLibraryRepository(context)
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val traversal = TrashedNote(null, ".Trash/items/$id/../outside.md", "outside.md", null, 0)
        assertTrue(runCatching { repository.permanentlyDelete(traversal) }.isFailure)

        val note = repository.createRootNote("Linked.md")
        repository.moveToTrash(note.note)
        val trashed = repository.listTrash().single()
        val trashFile = File(root, trashed.trashRelativePath)
        assertTrue(trashFile.delete())
        val outside = File(sandbox, "outside-note.md").apply { writeText("keep") }
        Files.createSymbolicLink(trashFile.toPath(), outside.toPath())

        assertTrue(runCatching { repository.permanentlyDelete(trashed) }.isFailure)
        assertEquals("keep", outside.readText())
        assertTrue(Files.isSymbolicLink(trashFile.toPath()))
        assertTrue(
            TrashManifestCodec.decode(File(root, ".Trash/manifest.v1").readText())
                .any { it.id == requireNotNull(trashed.manifestId) },
        )
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
