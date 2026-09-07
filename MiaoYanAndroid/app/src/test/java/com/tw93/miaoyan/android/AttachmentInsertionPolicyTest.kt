package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.AttachmentKind
import com.tw93.miaoyan.android.ui.AttachmentInsertionPolicy
import com.tw93.miaoyan.android.ui.AttachmentInsertionResult
import com.tw93.miaoyan.android.ui.DraftSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AttachmentInsertionPolicyTest {
    @Test
    fun replacesCapturedSelectionAndPlacesCursorAfterMarkdown() {
        val original = DraftSnapshot("Folder/note.md", 41, "before OLD after", 7, 10)
        val request = AttachmentInsertionPolicy.request(original, AttachmentKind.File)

        val result = AttachmentInsertionPolicy.apply(original, request, "[file](/files/file.pdf)")

        val applied = result as AttachmentInsertionResult.Applied
        assertEquals("before [file](/files/file.pdf) after", applied.text)
        assertEquals(30, applied.cursor)
    }

    @Test
    fun rejectsDifferentOwnerAndStaleRevision() {
        val original = DraftSnapshot("A.md", 7, "draft", 5, 5)
        val request = AttachmentInsertionPolicy.request(original, AttachmentKind.Image)

        assertSame(
            AttachmentInsertionResult.Stale,
            AttachmentInsertionPolicy.apply(original.copy(ownerNoteId = "B.md"), request, "![x](/i/x.png)"),
        )
        assertSame(
            AttachmentInsertionResult.Stale,
            AttachmentInsertionPolicy.apply(original.copy(revision = 8, text = "draft!"), request, "![x](/i/x.png)"),
        )
    }
}
