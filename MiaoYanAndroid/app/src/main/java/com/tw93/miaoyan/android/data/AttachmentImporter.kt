package com.tw93.miaoyan.android.data

import com.tw93.miaoyan.android.data.core.LibraryAccess
import com.tw93.miaoyan.android.data.core.LibraryMutationGate
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AttachmentMetadata(
    val displayName: String?,
    val mimeType: String?,
    val declaredSize: Long?,
)

interface AttachmentSource {
    fun metadata(): AttachmentMetadata

    fun openStream(): InputStream
}

data class ImportedAttachment(
    val operationId: String,
    val relativePath: String,
    val fileName: String,
    val markdown: String,
    val contentHash: String,
)

/** Atomic, streaming importer independent of the library repository implementation. */
class AttachmentImporter(
    private val libraryAccess: LibraryAccess = LibraryMutationGate,
) {

    suspend fun import(
        libraryRoot: File,
        noteRelativePath: String,
        kind: AttachmentKind,
        source: AttachmentSource,
    ): ImportedAttachment = withContext(Dispatchers.IO) {
        libraryAccess.withExclusiveAccess {
            val directory = AttachmentPathPolicy.resolveDirectory(libraryRoot, noteRelativePath, kind)
            val metadata = source.metadata()
            metadata.declaredSize?.let(AttachmentPathPolicy::validateSize)
            val preferred = AttachmentPathPolicy.sanitizeFileName(metadata.displayName, metadata.mimeType, kind)
            val fileName = AttachmentPathPolicy.collisionSafeName(preferred, directory.list().orEmpty().asList())
            val target = File(directory, fileName)
            require(target.canonicalFile.parentFile == directory && !target.exists()) {
                "The attachment destination is not safe."
            }
            val operationId = UUID.randomUUID().toString()
            val temporary = File(directory, ".miaoyan-$operationId.tmp")
            try {
                val copy = source.openStream().use { input ->
                    FileOutputStream(temporary).use { output -> copyAndSync(input, output) }
                }
                validateContent(kind, metadata.mimeType, copy.prefix)
                metadata.declaredSize?.let { declared ->
                    check(copy.bytes == declared) { "The selected file changed while it was being copied." }
                }
                check(temporary.isFile && temporary.length() == copy.bytes) { "The attachment copy was incomplete." }
                var published = false
                try {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    published = true
                    check(
                        target.isFile && target.length() == copy.bytes && sha256(target) == copy.contentHash,
                    ) { "The published attachment failed verification." }
                } catch (error: Throwable) {
                    if (published && target.isFile && runCatching { sha256(target) }.getOrNull() == copy.contentHash) {
                        target.delete()
                    }
                    throw error
                }
                val root = libraryRoot.canonicalFile
                ImportedAttachment(
                    operationId = operationId,
                    relativePath = target.relativeTo(root).invariantSeparatorsPath,
                    fileName = fileName,
                    markdown = AttachmentPathPolicy.markdown(kind, fileName),
                    contentHash = copy.contentHash,
                )
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun discard(libraryRoot: File, noteRelativePath: String, attachment: ImportedAttachment) =
        withContext(Dispatchers.IO) {
            libraryAccess.withExclusiveAccess {
                val kind = if (attachment.relativePath.substringBeforeLast('/').substringAfterLast('/') == "i") {
                    AttachmentKind.Image
                } else {
                    AttachmentKind.File
                }
                val directory = AttachmentPathPolicy.resolveDirectory(libraryRoot, noteRelativePath, kind)
                val file = File(libraryRoot.canonicalFile, attachment.relativePath).canonicalFile
                if (
                    file.isFile && file.parentFile == directory && file.name == attachment.fileName &&
                    !Files.isSymbolicLink(file.toPath())
                ) {
                    check(sha256(file) == attachment.contentHash) {
                        "The attachment changed after import and was not removed."
                    }
                    check(file.delete()) { "The unused attachment could not be removed." }
                }
            }
        }

    private fun copyAndSync(input: InputStream, output: FileOutputStream): CopyResult {
        val buffer = ByteArray(CopyBufferBytes)
        val prefix = ByteArray(SignatureBytes)
        var prefixSize = 0
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            AttachmentPathPolicy.validateSize(total)
            if (prefixSize < prefix.size) {
                val prefixCount = minOf(count, prefix.size - prefixSize)
                buffer.copyInto(prefix, prefixSize, 0, prefixCount)
                prefixSize += prefixCount
            }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        output.fd.sync()
        return CopyResult(total, prefix.copyOf(prefixSize), digest.digest().toHex())
    }

    private fun validateContent(kind: AttachmentKind, providerMime: String?, prefix: ByteArray) {
        val detected = ImageSignature.detect(prefix)
        when (kind) {
            AttachmentKind.Image -> {
                val declared = AttachmentPathPolicy.canonicalImageMime(providerMime)
                require(detected == declared) { "The selected image type does not match its contents." }
            }
            AttachmentKind.File -> require(detected == null) { "Use the image button for image attachments." }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CopyBufferBytes)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class CopyResult(val bytes: Long, val prefix: ByteArray, val contentHash: String)

    private companion object {
        const val CopyBufferBytes = 64 * 1024
        const val SignatureBytes = 64
    }
}

internal object ImageSignature {
    fun detect(bytes: ByteArray): String? = when {
        bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        bytes.startsWith(0xff, 0xd8, 0xff) -> "image/jpeg"
        bytes.ascii(0, 6) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP" -> "image/webp"
        bytes.ascii(0, 2) == "BM" -> "image/bmp"
        isAvif(bytes) -> "image/avif"
        else -> null
    }

    private fun isAvif(bytes: ByteArray): Boolean {
        if (bytes.ascii(4, 4) != "ftyp") return false
        var offset = 8
        while (offset + 4 <= bytes.size) {
            if (bytes.ascii(offset, 4) in setOf("avif", "avis")) return true
            offset += 4
        }
        return false
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { index -> (this[index].toInt() and 0xff) == expected[index] }

    private fun ByteArray.ascii(offset: Int, count: Int): String? =
        if (offset >= 0 && count >= 0 && offset + count <= size) {
            String(this, offset, count, Charsets.US_ASCII)
        } else {
            null
        }
}
