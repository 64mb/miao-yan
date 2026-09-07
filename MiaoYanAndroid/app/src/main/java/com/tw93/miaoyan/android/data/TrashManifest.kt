package com.tw93.miaoyan.android.data

import java.io.File
import java.util.Base64

data class TrashManifestEntry(
    val id: String,
    val originalRelativePath: String,
    val trashRelativePath: String,
    val deletedAtMillis: Long,
)

object TrashManifestCodec {
    private const val Header = "MiaoYanTrashManifest\t1"

    fun encode(entries: List<TrashManifestEntry>): String = buildString {
        appendLine(Header)
        entries.sortedBy { it.id }.forEach { entry ->
            appendLine(
                listOf(
                    entry.id,
                    entry.originalRelativePath,
                    entry.trashRelativePath,
                    entry.deletedAtMillis.toString(),
                ).joinToString("\t", transform = ::encodeField),
            )
        }
    }

    fun decode(content: String): List<TrashManifestEntry> {
        val lines = content.lineSequence().toList()
        if (lines.firstOrNull() != Header) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val encodedFields = line.split('\t')
            if (encodedFields.size != FieldCount) return@mapNotNull null
            val fields = encodedFields.map { decodeField(it) ?: return@mapNotNull null }
            TrashManifestEntry(
                id = fields[0],
                originalRelativePath = fields[1],
                trashRelativePath = fields[2],
                deletedAtMillis = fields[3].toLongOrNull() ?: return@mapNotNull null,
            )
        }
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(value: String): String? = runCatching {
        Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
    }.getOrNull()

    private const val FieldCount = 4
}

class TrashManifestStore(
    private val file: File,
    private val atomicWriter: (File, ByteArray) -> Unit,
) {
    fun load(): List<TrashManifestEntry> = runCatching {
        if (file.isFile) TrashManifestCodec.decode(file.readText(Charsets.UTF_8)) else emptyList()
    }.getOrDefault(emptyList())

    fun upsert(entry: TrashManifestEntry) {
        write(load().filterNot { it.id == entry.id } + entry)
    }

    fun remove(id: String) {
        write(load().filterNot { it.id == id })
    }

    private fun write(entries: List<TrashManifestEntry>) {
        atomicWriter(file, TrashManifestCodec.encode(entries).toByteArray(Charsets.UTF_8))
    }
}

object RestorePolicy {
    fun destinationRelativePath(originalRelativePath: String?, originalParentExists: Boolean): String {
        val name = originalRelativePath?.substringAfterLast('/') ?: return ""
        return if (originalParentExists) originalRelativePath else name
    }
}
