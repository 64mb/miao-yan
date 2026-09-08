package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.LocalImagePolicy
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalImagePolicyTest {
    @Test
    fun acceptsOnlyDirectEncodedAttachmentUrlsOnTheSyntheticOrigin() {
        assertEquals(
            "design notes.pdf",
            LocalImagePolicy.fileNameForAttachmentUrl(
                "https://appassets.androidplatform.net/files/design%20notes.pdf",
            ),
        )
        listOf(
            "https://appassets.androidplatform.net/files/../secret.txt",
            "https://appassets.androidplatform.net/files/%252e%252e.txt",
            "https://appassets.androidplatform.net/files/nested/file.txt",
            "https://evil.example/files/file.txt",
            "https://appassets.androidplatform.net/files/file.txt?download=1",
        ).forEach { assertNull(it, LocalImagePolicy.fileNameForAttachmentUrl(it)) }
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mapsMiaoYanImageToCanonicalAssetUrl() {
        val source = LocalImagePolicy.classifyMarkdownSource("/i/猫 photo+1.png")
        assertEquals(
            LocalImagePolicy.MarkdownSource.Local(
                fileName = "猫 photo+1.png",
                assetUrl = "https://appassets.androidplatform.net/i/%E7%8C%AB%20photo%2B1.png",
            ),
            source,
        )
        assertEquals(
            "猫 photo+1.png",
            LocalImagePolicy.fileNameForAssetUrl(
                "https://appassets.androidplatform.net/i/%E7%8C%AB%20photo%2B1.png",
            ),
        )
    }

    @Test
    fun recognizesExternalImagesWithoutTurningThemIntoAssetUrls() {
        val source = LocalImagePolicy.classifyMarkdownSource("https://images.example/note.png?size=2")
        assertEquals(LocalImagePolicy.MarkdownSource.External("https://images.example/note.png?size=2"), source)
        assertEquals(
            LocalImagePolicy.MarkdownSource.External("https://images.example/note.png"),
            LocalImagePolicy.classifyMarkdownSource("//images.example/note.png"),
        )
    }

    @Test
    fun requiresUserActivationAndSandboxForFutureExternalEmbeds() {
        LocalImagePolicy.ExternalMediaKind.entries.forEach { kind ->
            val media = requireNotNull(
                LocalImagePolicy.deferredExternalMedia("https://media.example/item", kind),
            )
            assertTrue(!LocalImagePolicy.canActivateExternalMedia(media, userActivated = false, sandboxed = true))
            if (kind == LocalImagePolicy.ExternalMediaKind.Iframe) {
                assertTrue(!LocalImagePolicy.canActivateExternalMedia(media, userActivated = true, sandboxed = false))
            }
            assertTrue(LocalImagePolicy.canActivateExternalMedia(media, userActivated = true, sandboxed = true))
        }
    }

    @Test
    fun rejectsTraversalAndNestedUrlPaths() {
        val rejected = listOf(
            "/i/../secret.png",
            "/i/%2e%2e",
            "/i/%2E%2E%2Fsecret.png",
            "/i/folder%2Fsecret.png",
            "/i/folder%5Csecret.png",
            "/i/%252e%252e%252fsecret.png",
            "/i/photo.png?ignored=true",
            "/i/photo.png#fragment",
            "/i/photo\u0000.png",
        )
        rejected.forEach { source ->
            assertTrue(
                "Expected rejection for $source",
                LocalImagePolicy.classifyMarkdownSource(source) is LocalImagePolicy.MarkdownSource.Unsupported,
            )
        }
    }

    @Test
    fun acceptsOnlyTheExactSyntheticOrigin() {
        val rejected = listOf(
            "http://appassets.androidplatform.net/i/photo.png",
            "https://appassets.androidplatform.net.evil.test/i/photo.png",
            "https://appassets.androidplatform.net:443/i/photo.png",
            "https://user@appassets.androidplatform.net/i/photo.png",
            "https://appassets.androidplatform.net/i/folder/photo.png",
            "https://appassets.androidplatform.net/i/%252e%252e%252fphoto.png",
            "https://appassets.androidplatform.net/i/photo.png?q=1",
        )
        rejected.forEach { assertNull(it, LocalImagePolicy.fileNameForAssetUrl(it)) }
    }

    @Test
    fun determinesAndValidatesImageMimeTypes() {
        assertEquals("image/png", LocalImagePolicy.mimeTypeFor("photo.PNG", null))
        assertEquals("image/jpeg", LocalImagePolicy.mimeTypeFor("photo.jpg", "image/jpg"))
        assertEquals("image/webp", LocalImagePolicy.mimeTypeFor("photo.webp", "application/octet-stream"))
        assertNull(LocalImagePolicy.mimeTypeFor("photo.png", "image/jpeg"))
        assertNull(LocalImagePolicy.mimeTypeFor("payload.html", "text/html"))
    }

    @Test
    fun resolvesImageBesideNestedNoteInsteadOfRootImage() {
        val root = temporaryFolder.newFolder("library")
        val rootImage = writeFile(root, "i/cover.png", "root")
        val nestedImage = writeFile(root, "Projects/Work/i/cover.png", "nested")
        writeFile(root, "Projects/Work/note.md", "# Note")

        val scope = requireNotNull(
            LocalImagePolicy.resolveNoteAssetScope(root, "Projects/Work/note.md"),
        )
        val resolved = LocalImagePolicy.resolveLocalImage(scope, "cover.png")

        assertEquals(nestedImage.canonicalFile, resolved)
        assertNotEquals(rootImage.canonicalFile, resolved)
    }

    @Test
    fun resolvesOnlyTheSelectedNotesDirectAttachmentFile() {
        val container = temporaryFolder.newFolder("attachment-library")
        val root = File(container, "root").apply { mkdirs() }
        writeFile(root, "Project/note.md", "inside")
        val attachment = writeFile(root, "Project/files/design.pdf", "pdf")
        writeFile(root, "files/design.pdf", "wrong")
        val outside = writeFile(container, "outside.txt", "outside")
        Files.createSymbolicLink(File(root, "Project/files/escape.txt").toPath(), outside.toPath())

        val scope = requireNotNull(LocalImagePolicy.resolveNoteAssetScope(root, "Project/note.md"))

        assertEquals(attachment.canonicalFile, LocalImagePolicy.resolveLocalAttachment(scope, "design.pdf"))
        assertNull(LocalImagePolicy.resolveLocalAttachment(scope, "escape.txt"))
        assertNull(LocalImagePolicy.resolveLocalAttachment(scope, "../note.md"))
    }

    @Test
    fun noteSwitchChangesFilesystemAssetScope() {
        val root = temporaryFolder.newFolder("switch-library")
        writeFile(root, "A/note.md", "A")
        writeFile(root, "B/note.md", "B")
        val firstImage = writeFile(root, "A/i/cover.png", "first")
        val secondImage = writeFile(root, "B/i/cover.png", "second")

        val first = requireNotNull(LocalImagePolicy.resolveNoteAssetScope(root, "A/note.md"))
        val second = requireNotNull(LocalImagePolicy.resolveNoteAssetScope(root, "B/note.md"))

        assertNotEquals(first, second)
        assertEquals(firstImage.canonicalFile, LocalImagePolicy.resolveLocalImage(first, "cover.png"))
        assertEquals(secondImage.canonicalFile, LocalImagePolicy.resolveLocalImage(second, "cover.png"))
    }

    @Test
    fun rejectsNoteParentAndImageSymlinkEscapes() {
        val container = temporaryFolder.newFolder("symlink-library")
        val root = File(container, "root").apply { mkdirs() }
        val outside = File(container, "outside").apply { mkdirs() }
        writeFile(outside, "linked/note.md", "outside")
        Files.createSymbolicLink(File(root, "Linked").toPath(), File(outside, "linked").toPath())
        assertNull(LocalImagePolicy.resolveNoteAssetScope(root, "Linked/note.md"))

        writeFile(root, "Project/note.md", "inside")
        val outsideImage = writeFile(outside, "images/secret.png", "secret")
        Files.createSymbolicLink(
            File(root, "Project/i").toPath(),
            requireNotNull(outsideImage.parentFile).toPath(),
        )
        val scope = requireNotNull(LocalImagePolicy.resolveNoteAssetScope(root, "Project/note.md"))
        assertNull(LocalImagePolicy.resolveLocalImage(scope, "secret.png"))
    }

    @Test
    fun rejectsFinalImageSymlinkEscapeAndNoteTraversal() {
        val container = temporaryFolder.newFolder("file-symlink-library")
        val root = File(container, "root").apply { mkdirs() }
        val outsideImage = writeFile(container, "outside.png", "outside")
        writeFile(root, "Project/note.md", "inside")
        File(root, "Project/i").mkdirs()
        Files.createSymbolicLink(File(root, "Project/i/escape.png").toPath(), outsideImage.toPath())

        val scope = requireNotNull(LocalImagePolicy.resolveNoteAssetScope(root, "Project/note.md"))
        assertNull(LocalImagePolicy.resolveLocalImage(scope, "escape.png"))
        assertNull(LocalImagePolicy.resolveNoteAssetScope(root, "../outside.md"))
    }

    private fun writeFile(root: File, relativePath: String, contents: String): File =
        File(root, relativePath).apply {
            parentFile?.mkdirs()
            writeText(contents)
        }
}
