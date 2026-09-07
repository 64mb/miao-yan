package com.tw93.miaoyan.android.data

import java.text.Normalizer
import java.util.Locale

enum class NameError {
    EMPTY,
    RESERVED,
    HIDDEN,
    INVALID_CHARACTERS,
    UNSUPPORTED_EXTENSION,
    TOO_LONG,
}

sealed interface NameResult {
    data class Valid(val name: String) : NameResult

    data class Invalid(val error: NameError) : NameResult
}

object NotePathPolicy {
    private val allowedExtensions = setOf("md", "markdown", "txt")
    private val reservedDirectoryNames = setOf(".git", ".trash", "trash", "i", "files")

    fun validateNoteName(input: String): NameResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return NameResult.Invalid(NameError.EMPTY)
        if (trimmed == "." || trimmed == "..") return NameResult.Invalid(NameError.RESERVED)
        if (trimmed.startsWith('.')) return NameResult.Invalid(NameError.HIDDEN)
        if (trimmed.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
            return NameResult.Invalid(NameError.INVALID_CHARACTERS)
        }

        val normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        val extension = normalized.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val withExtension = when {
            extension in allowedExtensions -> normalized
            '.' !in normalized -> "$normalized.md"
            else -> return NameResult.Invalid(NameError.UNSUPPORTED_EXTENSION)
        }
        if (withExtension.toByteArray(Charsets.UTF_8).size > MaximumNameBytes) {
            return NameResult.Invalid(NameError.TOO_LONG)
        }
        return NameResult.Valid(withExtension)
    }

    fun validateFolderName(input: String): NameResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return NameResult.Invalid(NameError.EMPTY)
        if (trimmed == "." || trimmed == "..") return NameResult.Invalid(NameError.RESERVED)
        if (trimmed.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
            return NameResult.Invalid(NameError.INVALID_CHARACTERS)
        }
        val normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        if (collisionKey(normalized) in reservedDirectoryNames) {
            return NameResult.Invalid(NameError.RESERVED)
        }
        if (normalized.startsWith('.')) return NameResult.Invalid(NameError.HIDDEN)
        if (normalized.toByteArray(Charsets.UTF_8).size > MaximumNameBytes) {
            return NameResult.Invalid(NameError.TOO_LONG)
        }
        return NameResult.Valid(normalized)
    }

    fun hasCollision(existingNames: Iterable<String>, proposedName: String): Boolean {
        val proposedKey = collisionKey(proposedName)
        return existingNames.any { collisionKey(it) == proposedKey }
    }

    fun hasSameName(first: String, second: String): Boolean = collisionKey(first) == collisionKey(second)

    fun isVisibleDirectory(name: String): Boolean {
        if (!isSafeSegment(name) || name.startsWith('.')) return false
        return collisionKey(name) !in setOf(".git", ".trash", "trash")
    }

    fun isNoteDirectory(name: String): Boolean =
        isVisibleDirectory(name) && collisionKey(name) !in reservedDirectoryNames

    fun isFolderPath(relativePath: String, allowRoot: Boolean = true): Boolean {
        if (relativePath.isEmpty()) return allowRoot
        val segments = validatedSegments(relativePath) ?: return false
        return segments.all(::isNoteDirectory)
    }

    fun isNote(relativePath: String): Boolean {
        val segments = validatedSegments(relativePath) ?: return false
        if (segments.dropLast(1).any { !isNoteDirectory(it) }) return false
        val name = segments.last()
        if (name.startsWith('.')) return false
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in allowedExtensions
    }

    fun isTransportFile(relativePath: String): Boolean {
        if (isNote(relativePath)) return true
        val segments = validatedSegments(relativePath) ?: return false
        if (segments.size < 2 || segments.last().startsWith('.')) return false
        val attachmentIndex = segments.lastIndex - 1
        if (collisionKey(segments[attachmentIndex]) !in setOf("i", "files")) return false
        return segments.take(attachmentIndex).all(::isNoteDirectory)
    }

    fun isTransportDirectory(relativePath: String): Boolean {
        val segments = validatedSegments(relativePath) ?: return false
        val attachmentIndexes = segments.indices.filter {
            collisionKey(segments[it]) in setOf("i", "files")
        }
        if (attachmentIndexes.size > 1) return false
        val attachmentIndex = attachmentIndexes.singleOrNull()
        return if (attachmentIndex == null) {
            segments.all(::isNoteDirectory)
        } else {
            attachmentIndex == segments.lastIndex && segments.take(attachmentIndex).all(::isNoteDirectory)
        }
    }

    fun normalizedTransportPath(relativePath: String): String? {
        val segments = validatedSegments(relativePath) ?: return null
        return segments.joinToString("/") { Normalizer.normalize(it, Normalizer.Form.NFC) }
    }

    fun collisionKey(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun validatedSegments(relativePath: String): List<String>? {
        if (relativePath.isEmpty() || relativePath.toByteArray(Charsets.UTF_8).size > MaximumPathBytes) return null
        if (relativePath.startsWith('/') || relativePath.any { it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
            return null
        }
        return relativePath.split('/').takeIf { segments ->
            segments.all {
                isSafeSegment(it) && !it.startsWith('.') && collisionKey(it) !in setOf(".git", ".trash", "trash")
            }
        }
    }

    private fun isSafeSegment(segment: String): Boolean =
        segment.isNotBlank() && segment == segment.trim() && segment != "." && segment != ".." &&
            segment.toByteArray(Charsets.UTF_8).size <= MaximumNameBytes &&
            segment.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }

    private const val MaximumNameBytes = 255
    private const val MaximumPathBytes = 4_096
}
