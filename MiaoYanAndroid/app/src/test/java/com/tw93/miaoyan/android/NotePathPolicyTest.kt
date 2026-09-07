package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.NotePathPolicy
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
}
