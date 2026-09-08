package com.tw93.miaoyan.android

import androidx.test.platform.app.InstrumentationRegistry
import com.tw93.miaoyan.android.data.AttachmentImporter
import com.tw93.miaoyan.android.data.AttachmentKind
import com.tw93.miaoyan.android.data.AttachmentMetadata
import com.tw93.miaoyan.android.data.AttachmentSource
import com.tw93.miaoyan.android.ui.AttachmentInsertionPolicy
import com.tw93.miaoyan.android.ui.AttachmentInsertionResult
import com.tw93.miaoyan.android.ui.DraftSnapshot
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AttachmentImporterInstrumentedTest {
    @Test
    fun atomicallyImportsIntoNoteRelativeFilesDirectory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "attachment-test-${UUID.randomUUID()}")
        try {
            File(root, "Nested/note.md").apply { parentFile?.mkdirs(); writeText("note") }
            val bytes = "device copy".toByteArray()
            val source = object : AttachmentSource {
                override fun metadata() = AttachmentMetadata("report final.pdf", "application/pdf", bytes.size.toLong())
                override fun openStream(): InputStream = ByteArrayInputStream(bytes)
            }

            val result = AttachmentImporter().import(root, "Nested/note.md", AttachmentKind.File, source)

            assertEquals("Nested/files/report-final.pdf", result.relativePath)
            assertEquals(bytes.toList(), File(root, result.relativePath).readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleOwnerIsRejectedOnDevice() {
        val snapshot = DraftSnapshot("one.md", 12, "draft", 5, 5)
        val request = AttachmentInsertionPolicy.request(snapshot, AttachmentKind.Image)
        assertSame(
            AttachmentInsertionResult.Stale,
            AttachmentInsertionPolicy.apply(snapshot.copy(ownerNoteId = "two.md"), request, "![x](/i/x.png)"),
        )
    }
}
