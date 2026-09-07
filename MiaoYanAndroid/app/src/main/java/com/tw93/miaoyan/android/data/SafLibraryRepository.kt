package com.tw93.miaoyan.android.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.OpenNote
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SafLibraryRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun retainPermission(uri: Uri) {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    suspend fun libraryName(treeUri: Uri): String = withContext(Dispatchers.IO) {
        queryDocument(treeUri, DocumentsContract.getTreeDocumentId(treeUri))?.displayName
            ?: "Markdown library"
    }

    suspend fun scan(treeUri: Uri): List<LibraryNote> = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val pending = ArrayDeque<Pair<String, String>>()
        val visited = mutableSetOf<String>()
        val notes = mutableListOf<LibraryNote>()
        pending.add(rootId to "")

        while (pending.isNotEmpty()) {
            val (parentId, parentPath) = pending.removeFirst()
            if (!visited.add(parentId)) continue
            queryChildren(treeUri, parentId).forEach { child ->
                val relativePath = if (parentPath.isEmpty()) child.displayName else "$parentPath/${child.displayName}"
                if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (NotePathPolicy.isVisibleDirectory(child.displayName)) {
                        pending.add(child.documentId to relativePath)
                    }
                } else if (NotePathPolicy.isNote(relativePath)) {
                    notes += LibraryNote(
                        documentId = child.documentId,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId),
                        relativePath = relativePath,
                        displayName = child.displayName,
                        modifiedAtMillis = child.modifiedAtMillis,
                        sizeBytes = child.sizeBytes,
                    )
                }
            }
        }

        notes.sortedWith(compareByDescending<LibraryNote> { it.modifiedAtMillis }.thenBy { it.relativePath.lowercase() })
    }

    suspend fun open(note: LibraryNote): OpenNote = withContext(Dispatchers.IO) {
        val bytes = readBytes(note.uri)
        OpenNote(note = note, text = bytes.toString(Charsets.UTF_8), contentHash = sha256(bytes))
    }

    suspend fun save(snapshot: OpenNote, newText: String): OpenNote = withContext(Dispatchers.IO) {
        val currentBytes = readBytes(snapshot.note.uri)
        check(sha256(currentBytes) == snapshot.contentHash) {
            "The note changed outside MiaoYan. Refresh it before saving."
        }

        val newBytes = newText.toByteArray(Charsets.UTF_8)
        require(newBytes.size <= MaximumNoteBytes) { "The note is larger than 8 MiB and cannot be saved by this prototype." }
        val recoveryFile = createRecoveryFile(snapshot.note, currentBytes)

        try {
            resolver.openOutputStream(snapshot.note.uri, "rwt")?.use { stream ->
                stream.write(newBytes)
                stream.flush()
            } ?: error("The selected document provider refused write access.")

            val verifiedBytes = readBytes(snapshot.note.uri)
            check(verifiedBytes.contentEquals(newBytes)) { "The document provider did not persist the complete note." }
            recoveryFile.delete()
            OpenNote(
                note = queryDocument(snapshot.note.uri, snapshot.note.documentId)?.let { row ->
                    snapshot.note.copy(modifiedAtMillis = row.modifiedAtMillis, sizeBytes = row.sizeBytes)
                } ?: snapshot.note.copy(sizeBytes = newBytes.size.toLong()),
                text = newText,
                contentHash = sha256(newBytes),
            )
        } catch (error: Throwable) {
            runCatching {
                resolver.openOutputStream(snapshot.note.uri, "rwt")?.use { it.write(currentBytes) }
            }
            throw error
        }
    }

    private fun createRecoveryFile(note: LibraryNote, bytes: ByteArray): File {
        val recoveryDirectory = File(context.noBackupFilesDir, "recovery").apply { mkdirs() }
        val file = File(recoveryDirectory, "${sha256(note.uri.toString().toByteArray())}.md")
        file.writeBytes(bytes)
        return file
    }

    private fun queryChildren(treeUri: Uri, parentId: String): List<DocumentRow> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return resolver.query(childrenUri, Projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    cursor.toDocumentRow()?.let(::add)
                }
            }
        }.orEmpty()
    }

    private fun queryDocument(treeUri: Uri, documentId: String): DocumentRow? {
        val uri = if (DocumentsContract.isTreeUri(treeUri)) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        } else {
            treeUri
        }
        return resolver.query(uri, Projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.toDocumentRow() else null
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
            modifiedAtMillis = getLong(getColumnIndexOrThrow(LastModified)),
            sizeBytes = getLong(getColumnIndexOrThrow(Size)),
        )
    }

    private fun readBytes(uri: Uri): ByteArray {
        val stream = resolver.openInputStream(uri)
            ?: error("The selected document provider refused read access.")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MaximumNoteBytes) {
                    "The note is larger than 8 MiB and cannot be edited by this prototype."
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class DocumentRow(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val modifiedAtMillis: Long,
        val sizeBytes: Long,
    )

    private companion object {
        const val MaximumNoteBytes = 8 * 1024 * 1024
        const val DocId = DocumentsContract.Document.COLUMN_DOCUMENT_ID
        const val DisplayName = DocumentsContract.Document.COLUMN_DISPLAY_NAME
        const val MimeType = DocumentsContract.Document.COLUMN_MIME_TYPE
        const val LastModified = DocumentsContract.Document.COLUMN_LAST_MODIFIED
        const val Size = DocumentsContract.Document.COLUMN_SIZE
        val Projection = arrayOf(DocId, DisplayName, MimeType, LastModified, Size)
    }
}
