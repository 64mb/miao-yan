package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.AttachmentKind
import com.tw93.miaoyan.android.data.AttachmentPathPolicy
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentPathPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun enforcesTwentyFiveMiBLimit() {
        AttachmentPathPolicy.validateSize(AttachmentPathPolicy.MaximumBytes)
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPathPolicy.validateSize(AttachmentPathPolicy.MaximumBytes + 1)
        }
        assertThrows(IllegalArgumentException::class.java) { AttachmentPathPolicy.validateSize(-1) }
    }

    @Test
    fun sanitizesNamesAndKeepsMimeCompatibleExtension() {
        assertEquals(
            "My-dangerous-photo.jpeg",
            AttachmentPathPolicy.sanitizeFileName("../My [dangerous] photo.JPEG", "image/jpeg", AttachmentKind.Image),
        )
        assertEquals(
            "report-final.pdf",
            AttachmentPathPolicy.sanitizeFileName("report final", "application/pdf", AttachmentKind.File),
        )
        assertEquals(
            "image.png",
            AttachmentPathPolicy.sanitizeFileName("..", "image/png", AttachmentKind.Image),
        )
        assertEquals(
            "not-really.jpg",
            AttachmentPathPolicy.sanitizeFileName("not-really.png", "image/jpeg", AttachmentKind.Image),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPathPolicy.sanitizeFileName("photo.jpg", "image/jpeg", AttachmentKind.File)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPathPolicy.sanitizeFileName("drawing.svg", "image/svg+xml", AttachmentKind.Image)
        }
    }

    @Test
    fun addsCaseInsensitiveCollisionSuffixWithoutOverwrite() {
        assertEquals(
            "photo-3.jpg",
            AttachmentPathPolicy.collisionSafeName("photo.jpg", listOf("Photo.JPG", "photo-2.jpg")),
        )
        assertEquals("fresh.pdf", AttachmentPathPolicy.collisionSafeName("fresh.pdf", listOf("other.pdf")))
    }

    @Test
    fun resolvesOnlySiblingDirectoryInsideCanonicalRoot() {
        val root = temporaryFolder.newFolder("library")
        val note = File(root, "Projects/note.md").apply { parentFile?.mkdirs(); writeText("note") }

        val images = AttachmentPathPolicy.resolveDirectory(root, "Projects/note.md", AttachmentKind.Image)

        assertEquals(File(note.parentFile, "i").canonicalFile, images)
        assertTrue(images.isDirectory)
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPathPolicy.resolveDirectory(root, "../outside.md", AttachmentKind.File)
        }
    }

    @Test
    fun rejectsSymlinkedNoteAndAttachmentDirectory() {
        val container = temporaryFolder.newFolder("symlinks")
        val root = File(container, "root").apply { mkdirs() }
        val outside = File(container, "outside").apply { mkdirs() }
        val outsideNote = File(outside, "note.md").apply { writeText("outside") }
        Files.createSymbolicLink(File(root, "linked.md").toPath(), outsideNote.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPathPolicy.resolveDirectory(root, "linked.md", AttachmentKind.Image)
        }

        File(root, "safe.md").writeText("safe")
        val outsideFiles = File(outside, "files").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "files").toPath(), outsideFiles.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPathPolicy.resolveDirectory(root, "safe.md", AttachmentKind.File)
        }
        assertFalse(File(outsideFiles, "payload").exists())
    }
}
