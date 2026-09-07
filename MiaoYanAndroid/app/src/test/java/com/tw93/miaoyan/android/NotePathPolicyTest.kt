package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.NotePathPolicy
import com.tw93.miaoyan.android.data.NameError
import com.tw93.miaoyan.android.data.NameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePathPolicyTest {
    @Test
    fun acceptsNestedMiaoYanNoteTypes() {
        assertTrue(NotePathPolicy.isNote("Journal/2026/September.md"))
        assertTrue(NotePathPolicy.isNote("draft.markdown"))
        assertTrue(NotePathPolicy.isNote("plain.txt"))
    }

    @Test
    fun rejectsTrashHiddenAndTraversalPaths() {
        assertFalse(NotePathPolicy.isNote("Trash/deleted.md"))
        assertFalse(NotePathPolicy.isNote(".Trash/deleted.md"))
        assertFalse(NotePathPolicy.isNote(".git/config.md"))
        assertFalse(NotePathPolicy.isNote("../outside.md"))
        assertFalse(NotePathPolicy.isNote("folder\\note.md"))
    }

    @Test
    fun rejectsUnsupportedFiles() {
        assertFalse(NotePathPolicy.isNote("photo.png"))
        assertFalse(NotePathPolicy.isNote("note.md.bak"))
    }

    @Test
    fun rejectsControlCharactersAndOversizedPaths() {
        assertFalse(NotePathPolicy.isNote("folder\u0000/note.md"))
        assertFalse(NotePathPolicy.isNote("a".repeat(4_090) + "/note.md"))
    }

    @Test
    fun normalizesAndCompletesValidNoteNames() {
        assertEquals(NameResult.Valid("Daily note.md"), NotePathPolicy.validateNoteName("  Daily note  "))
        assertEquals(NameResult.Valid("Café.md"), NotePathPolicy.validateNoteName("Cafe\u0301.md"))
        assertEquals(NameResult.Valid("README.MARKDOWN"), NotePathPolicy.validateNoteName("README.MARKDOWN"))
    }

    @Test
    fun rejectsUnsafeOrUnsupportedNoteNames() {
        assertEquals(NameResult.Invalid(NameError.EMPTY), NotePathPolicy.validateNoteName("  "))
        assertEquals(NameResult.Invalid(NameError.HIDDEN), NotePathPolicy.validateNoteName(".secret.md"))
        assertEquals(NameResult.Invalid(NameError.INVALID_CHARACTERS), NotePathPolicy.validateNoteName("folder/note.md"))
        assertEquals(NameResult.Invalid(NameError.UNSUPPORTED_EXTENSION), NotePathPolicy.validateNoteName("note.pdf"))
        assertEquals(NameResult.Invalid(NameError.TOO_LONG), NotePathPolicy.validateNoteName("я".repeat(130) + ".md"))
    }

    @Test
    fun detectsCaseAndUnicodeNormalizedCollisions() {
        assertTrue(NotePathPolicy.hasCollision(listOf("Résumé.md"), "RE\u0301SUME\u0301.MD"))
        assertFalse(NotePathPolicy.hasCollision(listOf("another.md"), "résumé.md"))
    }

    @Test
    fun transportAllowsNotesAndAdjacentAttachmentFoldersOnly() {
        assertTrue(NotePathPolicy.isTransportFile("Journal/2026/September.md"))
        assertTrue(NotePathPolicy.isTransportFile("Journal/2026/i/photo.webp"))
        assertTrue(NotePathPolicy.isTransportFile("files/document.pdf"))
        assertFalse(NotePathPolicy.isTransportFile("Journal/photo.webp"))
        assertFalse(NotePathPolicy.isTransportFile(".Trash/items/note.md"))
        assertFalse(NotePathPolicy.isTransportFile("Journal/i/nested/photo.webp"))
        assertFalse(NotePathPolicy.isNote("Journal/i/attachment.md"))
        assertEquals("Café/note.md", NotePathPolicy.normalizedTransportPath("Cafe\u0301/note.md"))
        assertEquals(null, NotePathPolicy.normalizedTransportPath(" Journal/note.md"))
    }
}
