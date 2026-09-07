package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.AttachmentImporter
import com.tw93.miaoyan.android.data.AttachmentKind
import com.tw93.miaoyan.android.data.AttachmentMetadata
import com.tw93.miaoyan.android.data.AttachmentPathPolicy
import com.tw93.miaoyan.android.data.AttachmentSource
import com.tw93.miaoyan.android.data.ImageSignature
import com.tw93.miaoyan.android.data.core.LibraryAccess
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun streamsToSiblingAndUsesCollisionSafeName() = runBlocking {
        val root = temporaryFolder.newFolder("library")
        File(root, "Folder/note.md").apply { parentFile?.mkdirs(); writeText("# Note") }
        File(root, "Folder/i").apply { mkdir(); resolve("photo.png").writeText("old") }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
        val imported = AttachmentImporter().import(
            root,
            "Folder/note.md",
            AttachmentKind.Image,
            ByteArraySource("photo.png", "image/png", png),
        )

        assertEquals("Folder/i/photo-2.png", imported.relativePath)
        assertEquals("![photo-2](/i/photo-2.png)", imported.markdown)
        assertEquals(png.toList(), File(root, imported.relativePath).readBytes().toList())
        assertEquals("old", File(root, "Folder/i/photo.png").readText())
        assertTrue(File(root, "Folder/i").list().orEmpty().none { it.startsWith(".miaoyan-") })
    }

    @Test
    fun rejectsMimeSignatureMismatchAndCleansTemporaryFile() = runBlocking {
        val root = temporaryFolder.newFolder("signature-mismatch")
        File(root, "note.md").writeText("note")
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                AttachmentImporter().import(
                    root,
                    "note.md",
                    AttachmentKind.Image,
                    ByteArraySource("fake.png", "image/png", jpeg),
                )
            }
        }
        val images = File(root, "i")
        assertFalse(File(images, "fake.png").exists())
        assertTrue(images.list().orEmpty().none { it.startsWith(".miaoyan-") })
    }

    @Test
    fun recognizesSupportedRasterMagicAndRejectsUnknownBytes() {
        assertEquals("image/png", ImageSignature.detect(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        assertEquals("image/jpeg", ImageSignature.detect(bytes(0xff, 0xd8, 0xff, 0xe0)))
        assertEquals("image/gif", ImageSignature.detect("GIF89a".toByteArray()))
        assertEquals("image/webp", ImageSignature.detect("RIFF1234WEBP".toByteArray()))
        assertEquals("image/bmp", ImageSignature.detect("BManything".toByteArray()))
        assertEquals("image/avif", ImageSignature.detect(bytes(0, 0, 0, 24) + "ftypavif".toByteArray()))
        assertNull(ImageSignature.detect("<svg onload='bad'>".toByteArray()))
    }

    @Test
    fun usesInjectedMutationGate() = runBlocking {
        val root = temporaryFolder.newFolder("gate")
        File(root, "note.md").writeText("note")
        val gate = RecordingMutationGate()

        AttachmentImporter(gate).import(
            root,
            "note.md",
            AttachmentKind.File,
            ByteArraySource("report.pdf", "application/pdf", "pdf".toByteArray()),
        )

        assertEquals(1, gate.invocations)
    }

    @Test
    fun staleDiscardDoesNotDeleteChangedAttachment() = runBlocking {
        val root = temporaryFolder.newFolder("hash-guard")
        File(root, "note.md").writeText("note")
        val importer = AttachmentImporter()
        val attachment = importer.import(
            root,
            "note.md",
            AttachmentKind.File,
            ByteArraySource("report.pdf", "application/pdf", "original".toByteArray()),
        )
        val file = File(root, attachment.relativePath)
        file.writeText("replacement")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { importer.discard(root, "note.md", attachment) }
        }
        assertEquals("replacement", file.readText())
    }

    @Test
    fun declaredSizeMismatchFailsClosedAndCleansTemporaryFile() = runBlocking {
        val root = temporaryFolder.newFolder("mismatch")
        File(root, "note.md").writeText("note")
        val source = object : AttachmentSource {
            override fun metadata() = AttachmentMetadata("report.pdf", "application/pdf", 99)
            override fun openStream(): InputStream = ByteArrayInputStream("short".toByteArray())
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { AttachmentImporter().import(root, "note.md", AttachmentKind.File, source) }
        }
        val files = File(root, "files")
        assertFalse(File(files, "report.pdf").exists())
        assertTrue(files.list().orEmpty().none { it.startsWith(".miaoyan-") })
    }

    @Test
    fun streamedSizeOverflowFailsClosedAndCleansTemporaryFile() = runBlocking {
        val root = temporaryFolder.newFolder("stream-overflow")
        File(root, "note.md").writeText("note")
        val source = object : AttachmentSource {
            override fun metadata() = AttachmentMetadata("payload.bin", "application/octet-stream", null)
            override fun openStream(): InputStream = FixedLengthInputStream(AttachmentPathPolicy.MaximumBytes + 1)
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { AttachmentImporter().import(root, "note.md", AttachmentKind.File, source) }
        }
        val files = File(root, "files")
        assertFalse(File(files, "payload.bin").exists())
        assertTrue(files.list().orEmpty().none { it.startsWith(".miaoyan-") })
    }

    private class ByteArraySource(
        private val name: String,
        private val mime: String,
        private val bytes: ByteArray,
    ) : AttachmentSource {
        override fun metadata() = AttachmentMetadata(name, mime, bytes.size.toLong())
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private class RecordingMutationGate : LibraryAccess {
        var invocations = 0

        override suspend fun <T> withExclusiveAccess(operation: suspend () -> T): T {
            invocations += 1
            return operation()
        }
    }

    private class FixedLengthInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            remaining -= 1
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
