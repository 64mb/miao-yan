package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.FolderMutationResult
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.OpenNote
import com.tw93.miaoyan.android.ui.LibraryViewModelFolderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewModelFolderPolicyTest {
    @Test
    fun renameRemapsSelectedOwnerAndKeepsDirtyDraftText() {
        val open = OpenNote(
            note = LibraryNote("Work/Drafts/Plan.md", "Work/Drafts/Plan.md", "Plan.md", 1, 4),
            text = "base",
            contentHash = "hash",
        )
        val mutation = FolderMutationResult("Work", "Archive")

        val remapped = requireNotNull(LibraryViewModelFolderPolicy.remapOpenNote(open, mutation))

        assertEquals("Archive/Drafts/Plan.md", remapped.note.id)
        assertEquals("Archive/Drafts/Plan.md", remapped.note.relativePath)
        assertEquals("base", remapped.text)
        assertEquals("hash", remapped.contentHash)
    }

    @Test
    fun unrelatedRenamePreservesOpenNoteIdentity() {
        val open = OpenNote(
            note = LibraryNote("Notes/Plan.md", "Notes/Plan.md", "Plan.md", 1, 4),
            text = "base",
            contentHash = "hash",
        )

        assertSame(
            open,
            LibraryViewModelFolderPolicy.remapOpenNote(open, FolderMutationResult("Work", "Archive")),
        )
    }

    @Test
    fun dirtyDraftBlocksTrashOnlyForItsOwningFolderTree() {
        val open = OpenNote(
            note = LibraryNote("Work/Drafts/Plan.md", "Work/Drafts/Plan.md", "Plan.md", 1, 4),
            text = "base",
            contentHash = "hash",
        )

        assertFalse(LibraryViewModelFolderPolicy.canTrashFolder(open, dirty = true, folderPath = "Work"))
        assertTrue(LibraryViewModelFolderPolicy.canTrashFolder(open, dirty = false, folderPath = "Work"))
        assertTrue(LibraryViewModelFolderPolicy.canTrashFolder(open, dirty = true, folderPath = "Other"))
        assertTrue(LibraryViewModelFolderPolicy.canTrashFolder(null, dirty = true, folderPath = "Work"))
    }
}
