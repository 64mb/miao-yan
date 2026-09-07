package com.tw93.miaoyan.android.data

import android.content.Context
import android.net.Uri
import com.tw93.miaoyan.android.data.core.LibraryAccess
import com.tw93.miaoyan.android.data.core.LibraryMutationGate
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.OpenNote
import com.tw93.miaoyan.android.model.TrashedNote
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RestoreResult(val restoredToRoot: Boolean)

data class TransferResult(val fileCount: Int)

class LocalLibraryRepository(
    private val context: Context,
    private val libraryAccess: LibraryAccess = LibraryMutationGate,
    private val demoLibrarySeeder: DemoLibrarySeeder = context.demoLibrarySeeder(),
    private val preferredLanguageTags: () -> List<String> = context::preferredLanguageTags,
) {
    val rootIdentity: String = RootIdentity

    private val root = File(context.filesDir, "libraries/default")
    private val trashRoot = File(root, TrashDirectoryName)
    private val manifestStore = TrashManifestStore(File(trashRoot, ManifestFileName), ::atomicWrite)
    private val transport = SafLibraryTransport(context)

    suspend fun scan(): List<LibraryNote> = withLibraryAccess {
        demoLibrarySeeder.seedIfEligible(preferredLanguageTags())
        ensureRoot()
        val notes = mutableListOf<LibraryNote>()
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            directory.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) return@forEach
                if (child.isDirectory && NotePathPolicy.isNoteDirectory(child.name)) {
                    pending.add(child)
                } else if (child.isFile) {
                    val relativePath = relativePath(child)
                    if (NotePathPolicy.isNote(relativePath)) notes += child.toLibraryNote(relativePath)
                }
            }
        }
        notes.sortedWith(compareByDescending<LibraryNote> { it.modifiedAtMillis }.thenBy { it.relativePath.lowercase() })
    }

    suspend fun listTrash(): List<TrashedNote> = withLibraryAccess {
        if (!trashRoot.isDirectory) return@withLibraryAccess emptyList()
        val entries = manifestStore.load().associateBy { it.trashRelativePath }
        val itemsRoot = File(trashRoot, TrashItemsDirectoryName)
        if (!itemsRoot.isDirectory) return@withLibraryAccess emptyList()
        buildList {
            itemsRoot.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }.forEach { item ->
                item.listFiles().orEmpty().filter { file ->
                    file.isFile && !Files.isSymbolicLink(file.toPath()) && NotePathPolicy.isNote(file.name)
                }.forEach { file ->
                    val trashPath = relativePath(file)
                    val entry = entries[trashPath]
                    add(
                        TrashedNote(
                            manifestId = entry?.id,
                            trashRelativePath = trashPath,
                            displayName = entry?.originalRelativePath?.substringAfterLast('/') ?: file.name,
                            originalRelativePath = entry?.originalRelativePath,
                            deletedAtMillis = entry?.deletedAtMillis ?: file.lastModified(),
                        ),
                    )
                }
            }
        }.sortedWith(compareByDescending<TrashedNote> { it.deletedAtMillis }.thenBy { it.displayName.lowercase() })
    }

    suspend fun open(note: LibraryNote): OpenNote = withLibraryAccess {
        val file = checkedNoteFile(note.relativePath)
        val bytes = readNoteBytes(file)
        OpenNote(note = file.toLibraryNote(note.relativePath), text = bytes.toString(Charsets.UTF_8), contentHash = sha256(bytes))
    }

    suspend fun createRootNote(inputName: String): OpenNote = withLibraryAccess {
        demoLibrarySeeder.claimWithoutSeeding()
        ensureRoot()
        val name = requireValidName(inputName)
        checkNoCollision(root, name)
        val target = File(root, name)
        atomicCreate(target, ByteArray(0))
        val note = target.toLibraryNote(name)
        OpenNote(note = note, text = "", contentHash = sha256(ByteArray(0)))
    }

    suspend fun rename(note: LibraryNote, inputName: String): LibraryNote = withLibraryAccess {
        val source = checkedNoteFile(note.relativePath)
        val name = requireValidName(inputName)
        if (source.name == name) return@withLibraryAccess source.toLibraryNote(note.relativePath)
        checkNoCollision(source.parentFile ?: root, name, excluding = source)
        val target = File(source.parentFile, name)
        atomicMove(source, target)
        val parentPath = note.relativePath.substringBeforeLast('/', "")
        val relativePath = if (parentPath.isEmpty()) name else "$parentPath/$name"
        target.toLibraryNote(relativePath)
    }

    suspend fun moveToTrash(note: LibraryNote) = withLibraryAccess {
        val source = checkedNoteFile(note.relativePath)
        val expectedHash = sha256File(source)
        val operationId = UUID.randomUUID().toString()
        val itemDirectory = File(File(trashRoot, TrashItemsDirectoryName), operationId)
        check(itemDirectory.mkdirs()) { "Could not create a recoverable Trash entry." }
        val target = File(itemDirectory, source.name)
        val trashRelativePath = relativePath(target)
        val entry = TrashManifestEntry(
            id = operationId,
            originalRelativePath = note.relativePath,
            trashRelativePath = trashRelativePath,
            deletedAtMillis = System.currentTimeMillis(),
        )
        manifestStore.upsert(entry)
        try {
            atomicMove(source, target)
        } catch (error: Throwable) {
            if (source.exists() && !target.exists()) manifestStore.remove(entry.id)
            throw error
        }
        check(sha256File(target) == expectedHash) { "The moved note failed verification but remains in Trash." }
    }

    suspend fun restore(trashed: TrashedNote): RestoreResult = withLibraryAccess {
        val source = checkedTrashFile(trashed.trashRelativePath)
        val manifestEntry = trashed.manifestId?.let { id -> manifestStore.load().firstOrNull { it.id == id } }
        val originalPath = manifestEntry?.originalRelativePath ?: trashed.originalRelativePath
        val originalParentPath = originalPath?.substringBeforeLast('/', "").orEmpty()
        val originalParent = if (originalParentPath.isEmpty()) root else safeResolve(originalParentPath)
        val originalParentExists = originalParent.isDirectory && !containsSymlink(originalParent)
        val destinationPath = RestorePolicy.destinationRelativePath(originalPath, originalParentExists)
            .ifEmpty { source.name }
        check(NotePathPolicy.isNote(destinationPath)) { "The original note path is no longer safe to restore." }
        val target = safeResolve(destinationPath)
        val targetParent = target.parentFile ?: root
        check(targetParent.isDirectory && !containsSymlink(targetParent)) { "The restore folder is not safe." }
        checkNoCollision(targetParent, target.name)
        val expectedHash = sha256File(source)
        atomicMove(source, target)
        check(sha256File(target) == expectedHash) { "The restored note failed content verification." }
        manifestEntry?.let { manifestStore.remove(it.id) }
        RestoreResult(restoredToRoot = !originalParentExists && originalParentPath.isNotEmpty())
    }

    suspend fun permanentlyDelete(trashed: TrashedNote) = withLibraryAccess {
        val itemId = TrashItemPathPolicy.itemId(trashed.trashRelativePath)
            ?: error("The Trash item path is invalid.")
        check(trashed.manifestId == null || trashed.manifestId == itemId) {
            "The Trash item identity no longer matches."
        }
        val source = safeResolve(trashed.trashRelativePath)
        check(source.toPath().normalize().startsWith(trashRoot.toPath().normalize())) {
            "The Trash path is invalid."
        }
        check(!containsSymlink(source)) { "Symbolic links cannot be permanently deleted from Trash." }
        val exists = Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)
        val manifestEntry = manifestStore.load().firstOrNull { it.id == itemId }
        if (manifestEntry != null) {
            check(manifestEntry.trashRelativePath == trashed.trashRelativePath) {
                "The Trash manifest path is invalid."
            }
        } else if (trashed.manifestId != null && exists) {
            error("The Trash manifest no longer matches this note.")
        }
        if (exists) {
            check(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Only a trashed note can be permanently deleted."
            }
            Files.delete(source.toPath())
        }
        manifestEntry?.let { manifestStore.remove(it.id) }
        source.parentFile?.delete()
    }

    suspend fun save(snapshot: OpenNote, newText: String): OpenNote = withLibraryAccess {
        val file = checkedNoteFile(snapshot.note.relativePath)
        val currentBytes = readNoteBytes(file)
        check(sha256(currentBytes) == snapshot.contentHash) {
            "The note changed outside MiaoYan. Refresh it before saving."
        }
        val newBytes = newText.toByteArray(Charsets.UTF_8)
        require(newBytes.size <= MaximumNoteBytes) { "The note is larger than 8 MiB and cannot be saved." }
        atomicWrite(file, newBytes)
        val verified = readNoteBytes(file)
        check(verified.contentEquals(newBytes)) { "The atomic note write failed verification." }
        OpenNote(file.toLibraryNote(snapshot.note.relativePath), newText, sha256(newBytes))
    }

    suspend fun importFrom(treeUri: Uri): TransferResult = withLibraryAccess {
        val staged = transport.stageImport(treeUri)
        try {
            demoLibrarySeeder.claimWithoutSeeding()
            ensureRoot()
            staged.files.forEach { stagedFile ->
                val target = safeResolve(stagedFile.relativePath)
                val parent = prepareImportParent(stagedFile.relativePath.substringBeforeLast('/', ""))
                check(target.parentFile == parent) { "The import target escaped the library root." }
                checkNoCollision(parent, target.name)
            }
            staged.files.forEach { stagedFile ->
                val target = safeResolve(stagedFile.relativePath)
                val parent = prepareImportParent(stagedFile.relativePath.substringBeforeLast('/', ""))
                check(target.parentFile == parent) { "The import target escaped the library root." }
                atomicCreateFrom(target, stagedFile.file)
            }
            TransferResult(staged.files.size)
        } finally {
            staged.directory.deleteRecursively()
        }
    }

    /** Future Git setup must cross this boundary before creating `.git` in the canonical root. */
    suspend fun claimForExternalInitialization() = withLibraryAccess {
        demoLibrarySeeder.claimWithoutSeeding()
        ensureRoot()
    }

    suspend fun exportTo(treeUri: Uri): TransferResult = withLibraryAccess {
        ensureRoot()
        val files = transportFiles()
        transport.export(treeUri, root, files)
        TransferResult(files.size)
    }

    private fun transportFiles(): List<String> {
        val files = mutableListOf<String>()
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            directory.listFiles().orEmpty().forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) return@forEach
                val relativePath = relativePath(child)
                if (child.isDirectory && NotePathPolicy.isTransportDirectory(relativePath)) {
                    pending.add(child)
                } else if (child.isFile && NotePathPolicy.isTransportFile(relativePath)) {
                    files += relativePath
                }
            }
        }
        return files.sorted()
    }

    private fun prepareImportParent(relativePath: String): File {
        if (relativePath.isEmpty()) return root
        var parent = root
        relativePath.split('/').forEach { segment ->
            val collision = parent.listFiles().orEmpty().firstOrNull {
                NotePathPolicy.hasSameName(it.name, segment)
            }
            parent = when {
                collision == null -> File(parent, segment).also {
                    check(it.mkdir()) { "Could not create imported folder $relativePath." }
                }
                collision.isDirectory && collision.name == segment && !Files.isSymbolicLink(collision.toPath()) -> collision
                else -> error("The imported folder $relativePath collides with an existing item.")
            }
        }
        return parent
    }

    private fun ensureRoot() {
        check((root.isDirectory || root.mkdirs()) && !Files.isSymbolicLink(root.toPath())) {
            "The private MiaoYan library is unavailable."
        }
    }

    private fun checkedNoteFile(relativePath: String): File {
        check(NotePathPolicy.isNote(relativePath)) { "The note path is not valid." }
        val file = safeResolve(relativePath)
        check(file.isFile && !containsSymlink(file)) { "The note no longer exists in the private library." }
        return file
    }

    private fun checkedTrashFile(relativePath: String): File {
        check(TrashItemPathPolicy.itemId(relativePath) != null) { "The Trash item path is invalid." }
        val file = safeResolve(relativePath)
        check(file.toPath().normalize().startsWith(trashRoot.toPath().normalize())) { "The Trash path is invalid." }
        check(file.isFile && !containsSymlink(file) && NotePathPolicy.isNote(file.name)) {
            "The note is no longer present in Trash."
        }
        return file
    }

    private fun safeResolve(relativePath: String): File {
        check(!relativePath.startsWith('/') && relativePath.none { it == '\\' || it.code < 0x20 }) {
            "The relative path is invalid."
        }
        val target = File(root, relativePath).toPath().normalize()
        check(target.startsWith(root.toPath().normalize())) { "The path escaped the private library." }
        return target.toFile()
    }

    private fun containsSymlink(file: File): Boolean {
        var current: File? = file
        while (current != null && current.toPath().normalize().startsWith(root.toPath().normalize())) {
            if (Files.isSymbolicLink(current.toPath())) return true
            if (current == root) break
            current = current.parentFile
        }
        return false
    }

    private fun checkNoCollision(directory: File, name: String, excluding: File? = null) {
        val existingNames = directory.listFiles().orEmpty().filterNot { it == excluding }.map { it.name }
        check(!NotePathPolicy.hasCollision(existingNames, name)) {
            "An item named $name already exists here. Nothing was overwritten."
        }
    }

    private fun requireValidName(input: String): String = when (val result = NotePathPolicy.validateNoteName(input)) {
        is NameResult.Valid -> result.name
        is NameResult.Invalid -> throw IllegalArgumentException(result.error.message)
    }

    private fun atomicCreate(target: File, bytes: ByteArray) {
        check(!target.exists()) { "The destination already exists. Nothing was overwritten." }
        target.parentFile?.let { check(it.isDirectory) { "The destination folder does not exist." } }
        val temporary = File(target.parentFile, ".miaoyan-${UUID.randomUUID()}.tmp")
        writeSynced(temporary, bytes)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    private fun atomicCreateFrom(target: File, source: File) {
        check(!target.exists()) { "The destination already exists. Nothing was overwritten." }
        target.parentFile?.let { check(it.isDirectory) { "The destination folder does not exist." } }
        val temporary = File(target.parentFile, ".miaoyan-${UUID.randomUUID()}.tmp")
        val expectedHash = sha256File(source)
        source.inputStream().use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output, CopyBufferBytes)
                output.fd.sync()
            }
        }
        try {
            check(sha256File(temporary) == expectedHash) { "The staged import failed verification." }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.let { parent -> check(parent.isDirectory || parent.mkdirs()) { "Could not create the data folder." } }
        val temporary = File(target.parentFile, ".miaoyan-${UUID.randomUUID()}.tmp")
        writeSynced(temporary, bytes)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun atomicMove(source: File, target: File) {
        check(source.exists()) { "The source note no longer exists." }
        check(!target.exists()) { "The destination already exists. Nothing was overwritten." }
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun readNoteBytes(file: File): ByteArray {
        require(file.length() <= MaximumNoteBytes) { "The note is larger than 8 MiB and cannot be edited." }
        return file.readBytes().also { bytes ->
            require(bytes.size <= MaximumNoteBytes) { "The note is larger than 8 MiB and cannot be edited." }
        }
    }

    private fun File.toLibraryNote(relativePath: String) = LibraryNote(
        id = relativePath,
        relativePath = relativePath,
        displayName = name,
        modifiedAtMillis = lastModified(),
        sizeBytes = length(),
    )

    private fun relativePath(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    private fun sha256File(file: File): String {
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val NameError.message: String
        get() = when (this) {
            NameError.EMPTY -> "Enter a note name."
            NameError.RESERVED -> "That note name is reserved."
            NameError.HIDDEN -> "Note names cannot start with a dot."
            NameError.INVALID_CHARACTERS -> "Note names cannot contain slashes or control characters."
            NameError.UNSUPPORTED_EXTENSION -> "Use a .md, .markdown, or .txt extension."
            NameError.TOO_LONG -> "The note name is too long."
        }

    private suspend fun <T> withLibraryAccess(block: suspend () -> T): T =
        libraryAccess.withExclusiveAccess {
            withContext(Dispatchers.IO) { block() }
        }

    private companion object {
        const val RootIdentity = "app-private://libraries/default"
        const val MaximumNoteBytes = 8 * 1024 * 1024
        const val CopyBufferBytes = 64 * 1024
        const val TrashDirectoryName = ".Trash"
        const val TrashItemsDirectoryName = "items"
        const val ManifestFileName = "manifest.v1"
    }
}
