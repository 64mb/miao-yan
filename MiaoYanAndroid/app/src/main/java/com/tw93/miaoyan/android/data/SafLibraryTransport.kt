package com.tw93.miaoyan.android.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class StagedImport(val directory: File, val files: List<StagedFile>)

data class StagedFile(val relativePath: String, val file: File)

class SafLibraryTransport(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun stageImport(treeUri: Uri): StagedImport {
        val stagingDirectory = File(context.cacheDir, "library-import/${UUID.randomUUID()}")
        check(stagingDirectory.mkdirs()) { "Could not create the private import staging folder." }
        try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val pending = ArrayDeque<Pair<String, String>>()
            val visited = mutableSetOf<String>()
            val pathKeys = mutableSetOf<String>()
            val staged = mutableListOf<StagedFile>()
            pending.add(rootId to "")

            while (pending.isNotEmpty()) {
                val (parentId, parentPath) = pending.removeFirst()
                check(visited.add(parentId)) { "The import tree contains a directory loop." }
                queryChildren(treeUri, parentId).forEach { child ->
                    val rawPath = if (parentPath.isEmpty()) child.displayName else "$parentPath/${child.displayName}"
                    val relativePath = NotePathPolicy.normalizedTransportPath(rawPath) ?: return@forEach
                    if (child.isDirectory) {
                        if (NotePathPolicy.isTransportDirectory(relativePath)) pending.add(child.documentId to relativePath)
                    } else if (NotePathPolicy.isTransportFile(relativePath)) {
                        check(pathKeys.add(pathCollisionKey(relativePath))) {
                            "The import contains names that collide by case or Unicode normalization: $relativePath"
                        }
                        check(child.sizeBytes <= MaximumTransportFileBytes || child.sizeBytes <= 0) {
                            "The imported file $relativePath is larger than 25 MiB."
                        }
                        val destination = safeStageFile(stagingDirectory, relativePath)
                        destination.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Could not stage $relativePath." } }
                        copyIntoStaging(documentUri(treeUri, child.documentId), destination, relativePath)
                        staged += StagedFile(relativePath, destination)
                    }
                }
            }
            return StagedImport(stagingDirectory, staged.sortedBy { it.relativePath })
        } catch (error: Throwable) {
            stagingDirectory.deleteRecursively()
            throw error
        }
    }

    fun export(treeUri: Uri, libraryRoot: File, relativePaths: List<String>) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        relativePaths.forEach { relativePath ->
            check(NotePathPolicy.isTransportFile(relativePath)) { "The export path $relativePath is not allowed." }
            val source = File(libraryRoot, relativePath).toPath().normalize()
            check(source.startsWith(libraryRoot.toPath().normalize()) && source.toFile().isFile) {
                "The exported file $relativePath no longer exists."
            }
            val parentPath = relativePath.substringBeforeLast('/', "")
            val parentId = ensureExportDirectory(treeUri, rootId, parentPath)
            val name = relativePath.substringAfterLast('/')
            val siblings = queryChildren(treeUri, parentId)
            check(!NotePathPolicy.hasCollision(siblings.map { it.displayName }, name)) {
                "The export destination already contains $relativePath. Nothing was overwritten."
            }
            val created = DocumentsContract.createDocument(
                resolver,
                documentUri(treeUri, parentId),
                mimeTypeFor(name),
                name,
            ) ?: error("The document provider could not export $relativePath.")
            copyToProvider(source.toFile(), created, relativePath)
            val row = queryDocument(created, DocumentsContract.getDocumentId(created))
                ?: error("The exported file $relativePath could not be read back.")
            check(NotePathPolicy.hasSameName(row.displayName, name)) {
                "The document provider changed the exported name ${row.displayName}."
            }
        }
    }

    private fun ensureExportDirectory(treeUri: Uri, rootId: String, relativePath: String): String {
        if (relativePath.isEmpty()) return rootId
        var parentId = rootId
        var currentPath = ""
        relativePath.split('/').forEach { segment ->
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            check(NotePathPolicy.isTransportDirectory(currentPath)) { "The export folder $currentPath is not allowed." }
            val collision = queryChildren(treeUri, parentId).firstOrNull {
                NotePathPolicy.hasSameName(it.displayName, segment)
            }
            parentId = when {
                collision == null -> {
                    val created = DocumentsContract.createDocument(
                        resolver,
                        documentUri(treeUri, parentId),
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        segment,
                    ) ?: error("The provider could not create export folder $currentPath.")
                    DocumentsContract.getDocumentId(created)
                }
                collision.isDirectory && collision.displayName == segment -> collision.documentId
                else -> error("The export folder $currentPath collides with an existing item.")
            }
        }
        return parentId
    }

    private fun copyIntoStaging(sourceUri: Uri, destination: File, relativePath: String) {
        val input = resolver.openInputStream(sourceUri) ?: error("The provider refused to read $relativePath.")
        input.use { source ->
            FileOutputStream(destination).use { target ->
                val buffer = ByteArray(CopyBufferBytes)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MaximumTransportFileBytes) { "The imported file $relativePath is larger than 25 MiB." }
                    target.write(buffer, 0, count)
                }
                target.fd.sync()
            }
        }
    }

    private fun copyToProvider(source: File, targetUri: Uri, relativePath: String) {
        val expectedHash = sha256File(source)
        val output = resolver.openOutputStream(targetUri, "rwt") ?: error("The provider refused to write $relativePath.")
        source.inputStream().use { input ->
            output.use { target ->
                input.copyTo(target, CopyBufferBytes)
                target.flush()
            }
        }
        check(sha256Uri(targetUri) == expectedHash) { "The exported file $relativePath failed verification." }
    }

    private fun safeStageFile(stagingDirectory: File, relativePath: String): File {
        val path = File(stagingDirectory, relativePath).toPath().normalize()
        check(path.startsWith(stagingDirectory.toPath().normalize())) { "The import path escaped staging." }
        return path.toFile()
    }

    private fun pathCollisionKey(relativePath: String): String =
        relativePath.split('/').joinToString("/") { NotePathPolicy.collisionKey(it) }

    private fun queryChildren(treeUri: Uri, parentId: String): List<DocumentRow> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = resolver.query(childrenUri, Projection, null, null, null)
            ?: error("The document provider refused to list the selected folder.")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.toDocumentRow() ?: error("The provider returned incomplete document metadata."))
                }
            }
        }
    }

    private fun queryDocument(treeUri: Uri, documentId: String): DocumentRow? {
        val uri = if (DocumentsContract.isTreeUri(treeUri)) documentUri(treeUri, documentId) else treeUri
        val cursor = resolver.query(uri, Projection, null, null, null)
            ?: error("The document provider refused to verify an exported file.")
        return cursor.use {
            if (it.moveToFirst()) it.toDocumentRow() else null
        }
    }

    private fun Cursor.toDocumentRow(): DocumentRow? {
        val documentId = getString(getColumnIndexOrThrow(DocId)) ?: return null
        val displayName = getString(getColumnIndexOrThrow(DisplayName)) ?: return null
        val mimeType = getString(getColumnIndexOrThrow(MimeType)) ?: return null
        return DocumentRow(
            documentId = documentId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = getLong(getColumnIndexOrThrow(Size)),
        )
    }

    private fun sha256Uri(uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: error("The provider refused verification read access.")
        return input.use(::sha256Stream)
    }

    private fun sha256File(file: File): String = file.inputStream().use(::sha256Stream)

    private fun sha256Stream(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CopyBufferBytes)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "md", "markdown" -> "text/markdown"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private data class DocumentRow(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private companion object {
        const val CopyBufferBytes = 64 * 1024
        const val MaximumTransportFileBytes = 25L * 1024 * 1024
        const val DocId = DocumentsContract.Document.COLUMN_DOCUMENT_ID
        const val DisplayName = DocumentsContract.Document.COLUMN_DISPLAY_NAME
        const val MimeType = DocumentsContract.Document.COLUMN_MIME_TYPE
        const val Size = DocumentsContract.Document.COLUMN_SIZE
        val Projection = arrayOf(DocId, DisplayName, MimeType, Size)
    }
}
