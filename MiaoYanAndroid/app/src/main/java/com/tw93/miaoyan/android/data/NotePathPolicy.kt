package com.tw93.miaoyan.android.data

object NotePathPolicy {
    private val allowedExtensions = setOf("md", "markdown", "txt")
    private val trashNames = setOf("trash", ".trash")

    fun isVisibleDirectory(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        if (name.any { it == '/' || it == '\\' || it.code < 0x20 }) return false
        if (name.startsWith('.')) return false
        return name.lowercase() !in trashNames
    }

    fun isNote(relativePath: String): Boolean {
        if (relativePath.toByteArray(Charsets.UTF_8).size > 4_096) return false
        if (relativePath.any { it == '\\' || it.code < 0x20 }) return false
        val segments = relativePath.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return false
        if (segments.dropLast(1).any { !isVisibleDirectory(it) }) return false
        val name = segments.last()
        if (name.startsWith('.')) return false
        return name.substringAfterLast('.', missingDelimiterValue = "").lowercase() in allowedExtensions
    }
}
