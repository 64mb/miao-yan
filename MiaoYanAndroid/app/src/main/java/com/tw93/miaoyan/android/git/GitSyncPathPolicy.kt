package com.tw93.miaoyan.android.git

import java.util.Locale

object GitSyncPathPolicy {
    private val noteExtensions = setOf("md", "markdown", "txt")
    private val attachmentDirectories = setOf("i", "files")
    private val trashDirectories = setOf("trash", ".trash")

    fun isAllowed(path: String): Boolean {
        if (path.isBlank() || path.toByteArray(Charsets.UTF_8).size > 4_096) return false
        if (path.any { it == '\\' || it.code < 0x20 }) return false
        if (path == ".gitignore") return true
        val segments = path.split('/')
        if (
            segments.any {
                it.isBlank() || it == "." || it == ".." || it.startsWith('.') ||
                    it.lowercase(Locale.ROOT) in trashDirectories
            }
        ) return false
        val fileName = segments.last()
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return extension in noteExtensions || segments.dropLast(1).any { it in attachmentDirectories }
    }

    fun isSafeDirectoryPath(path: String): Boolean {
        if (path.isBlank() || path.toByteArray(Charsets.UTF_8).size > 4_096) return false
        if (path.any { it == '\\' || it.code < 0x20 }) return false
        return path.split('/').all {
            it.isNotBlank() && it != "." && it != ".." && !it.startsWith('.') &&
                it.lowercase(Locale.ROOT) !in trashDirectories
        }
    }

    fun collisionKey(path: String): String = path.lowercase(Locale.ROOT)

    fun isAttachment(path: String): Boolean = path.split('/').dropLast(1).any { it in attachmentDirectories }
}
