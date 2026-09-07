package com.tw93.miaoyan.android.data

import java.io.File
import java.nio.file.Files
import java.text.Normalizer
import java.util.Locale

enum class AttachmentKind(val directoryName: String) {
    Image("i"),
    File("files"),
}

/** Filesystem and naming policy for note-relative attachments. */
object AttachmentPathPolicy {
    const val MaximumBytes: Long = 25L * 1024L * 1024L

    fun validateSize(bytes: Long) {
        require(bytes in 0..MaximumBytes) { "Attachments must be no larger than 25 MiB." }
    }

    fun sanitizeFileName(displayName: String?, mimeType: String?, kind: AttachmentKind): String {
        val rawName = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val normalized = Normalizer.normalize(rawName, Normalizer.Form.NFC)
        val originalExtension = normalized.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(ExtensionPattern) }
        val extension = when (kind) {
            AttachmentKind.Image -> imageExtension(mimeType, originalExtension)
            AttachmentKind.File -> {
                require(normalizedMime(mimeType)?.startsWith("image/") != true) {
                    "Use the image button for image attachments."
                }
                originalExtension ?: extensionForMime(mimeType)
            }
        }
        val rawStem = if (originalExtension == null) normalized else normalized.dropLast(originalExtension.length + 1)
        val stem = buildString {
            var pendingSeparator = false
            rawStem.forEach { character ->
                when {
                    character.isLetterOrDigit() -> {
                        if (pendingSeparator && isNotEmpty()) append('-')
                        append(character)
                        pendingSeparator = false
                    }
                    character == '-' || character == '_' -> {
                        if (isNotEmpty() && last() !in "-_") append(character)
                        pendingSeparator = false
                    }
                    else -> pendingSeparator = true
                }
            }
        }.trim('-', '_').ifEmpty { if (kind == AttachmentKind.Image) "image" else "attachment" }
        val suffix = extension?.let { ".$it" }.orEmpty()
        val trimmedStem = trimUtf8(stem, MaximumNameBytes - suffix.toByteArray().size)
        return trimmedStem.ifEmpty { "attachment" } + suffix
    }

    fun canonicalImageMime(mimeType: String?): String =
        ImageMimeExtensions[normalizedMime(mimeType)]?.first
            ?: throw IllegalArgumentException("Choose a supported image (PNG, JPEG, GIF, WebP, AVIF, or BMP).")

    fun collisionSafeName(preferred: String, existingNames: Collection<String>): String {
        val occupied = existingNames.mapTo(mutableSetOf(), ::collisionKey)
        if (collisionKey(preferred) !in occupied) return preferred
        val extension = preferred.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        val stem = if (extension == null) preferred else preferred.dropLast(extension.length + 1)
        var counter = 2
        while (true) {
            val suffix = "-$counter" + extension?.let { ".$it" }.orEmpty()
            val candidate = trimUtf8(stem, MaximumNameBytes - suffix.toByteArray().size) + suffix
            if (collisionKey(candidate) !in occupied) return candidate
            counter += 1
        }
    }

    fun resolveDirectory(libraryRoot: File, noteRelativePath: String, kind: AttachmentKind): File {
        require(NotePathPolicy.isNote(noteRelativePath)) { "The note path is not valid." }
        val root = libraryRoot.canonicalFile
        require(root.isDirectory && !Files.isSymbolicLink(libraryRoot.toPath())) {
            "The private MiaoYan library is unavailable."
        }
        val unresolvedNote = File(root, noteRelativePath)
        val note = unresolvedNote.canonicalFile
        require(
            note.isFile && isWithin(note, root) && !containsSymlink(unresolvedNote, root),
        ) { "The note no longer exists in the private library." }
        val parent = requireNotNull(note.parentFile)
        require(isWithin(parent, root)) { "The note folder escaped the private library." }

        val unresolvedDirectory = File(parent, kind.directoryName)
        if (!unresolvedDirectory.exists()) {
            require(unresolvedDirectory.mkdir()) { "Could not create the attachment folder." }
        }
        val directory = unresolvedDirectory.canonicalFile
        require(
            directory.isDirectory && directory.parentFile == parent && isWithin(directory, root) &&
                !Files.isSymbolicLink(unresolvedDirectory.toPath()),
        ) { "The attachment folder is not safe." }
        return directory
    }

    fun markdown(kind: AttachmentKind, fileName: String): String = when (kind) {
        AttachmentKind.Image -> "![${escapeLabel(fileName.substringBeforeLast('.', fileName))}](/i/$fileName)"
        AttachmentKind.File -> "[${escapeLabel(fileName)}](/files/$fileName)"
    }

    private fun imageExtension(mimeType: String?, original: String?): String {
        val canonical = ImageMimeExtensions[normalizedMime(mimeType)]
            ?: throw IllegalArgumentException("Choose a supported image (PNG, JPEG, GIF, WebP, AVIF, or BMP).")
        val originalMime = ImageExtensionMimes[original]
        return if (original != null && originalMime == canonical.first) original else canonical.second
    }

    private fun extensionForMime(mimeType: String?): String? = GenericMimeExtensions[normalizedMime(mimeType)]

    private fun normalizedMime(mimeType: String?): String? =
        mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)

    private fun trimUtf8(value: String, maximumBytes: Int): String {
        var result = value
        while (result.isNotEmpty() && result.toByteArray(Charsets.UTF_8).size > maximumBytes) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun collisionKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun escapeLabel(value: String): String =
        value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")

    private fun containsSymlink(candidate: File, root: File): Boolean {
        var current: File? = candidate
        while (current != null && current.toPath().normalize().startsWith(root.toPath().normalize())) {
            if (Files.isSymbolicLink(current.toPath())) return true
            if (current.toPath().normalize() == root.toPath().normalize()) return false
            current = current.parentFile
        }
        return true
    }

    private fun isWithin(candidate: File, root: File): Boolean = candidate.toPath().startsWith(root.toPath())

    private const val MaximumNameBytes = 180
    private val ExtensionPattern = Regex("[a-z0-9]{1,10}")
    private val ImageMimeExtensions = mapOf(
        "image/png" to ("image/png" to "png"),
        "image/x-png" to ("image/png" to "png"),
        "image/jpeg" to ("image/jpeg" to "jpg"),
        "image/jpg" to ("image/jpeg" to "jpg"),
        "image/pjpeg" to ("image/jpeg" to "jpg"),
        "image/gif" to ("image/gif" to "gif"),
        "image/webp" to ("image/webp" to "webp"),
        "image/avif" to ("image/avif" to "avif"),
        "image/bmp" to ("image/bmp" to "bmp"),
        "image/x-ms-bmp" to ("image/bmp" to "bmp"),
    )
    private val ImageExtensionMimes = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
    )
    private val GenericMimeExtensions = mapOf(
        "application/pdf" to "pdf",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "application/json" to "json",
        "application/zip" to "zip",
    )
}
