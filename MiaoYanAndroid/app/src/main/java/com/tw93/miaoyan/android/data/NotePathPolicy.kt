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
    private val trashNames = setOf("trash", ".trash")
    private val attachmentDirectoryNames = setOf("i", "files")

    fun validateNoteName(input: String): NameResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return NameResult.Invalid(NameError.EMPTY)
        if (trimmed == "." || trimmed == "..") return NameResult.Invalid(NameError.RESERVED)
        if (trimmed.startsWith('.')) return NameResult.Invalid(NameError.HIDDEN)
        if (trimmed.any { it == '/' || it == '\\' || it.code < 0x20 }) {
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

    fun hasCollision(existingNames: Iterable<String>, proposedName: String): Boolean {
        val proposedKey = collisionKey(proposedName)
        return existingNames.any { collisionKey(it) == proposedKey }
    }

    fun hasSameName(first: String, second: String): Boolean = collisionKey(first) == collisionKey(second)

    fun isVisibleDirectory(name: String): Boolean {
        if (!isSafeSegment(name) || name.startsWith('.')) return false
        return name.lowercase(Locale.ROOT) !in trashNames
    }

    fun isNoteDirectory(name: String): Boolean =
        isVisibleDirectory(name) && name.lowercase(Locale.ROOT) !in attachmentDirectoryNames

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
        if (segments[attachmentIndex].lowercase(Locale.ROOT) !in attachmentDirectoryNames) return false
        return segments.take(attachmentIndex).all(::isNoteDirectory)
    }

    fun isTransportDirectory(relativePath: String): Boolean {
        val segments = validatedSegments(relativePath) ?: return false
        val attachmentIndexes = segments.indices.filter {
            segments[it].lowercase(Locale.ROOT) in attachmentDirectoryNames
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
        if (relativePath.startsWith('/') || relativePath.any { it == '\\' || it.code < 0x20 }) return null
        return relativePath.split('/').takeIf { segments ->
            segments.all { isSafeSegment(it) && !it.startsWith('.') && it.lowercase(Locale.ROOT) !in trashNames }
        }
    }

    private fun isSafeSegment(segment: String): Boolean =
        segment.isNotBlank() && segment == segment.trim() && segment != "." && segment != ".." &&
            segment.toByteArray(Charsets.UTF_8).size <= MaximumNameBytes &&
            segment.none { it == '/' || it == '\\' || it.code < 0x20 }

    private const val MaximumNameBytes = 255
    private const val MaximumPathBytes = 4_096
}
