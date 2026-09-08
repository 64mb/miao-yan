package com.tw93.miaoyan.android.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

/** Keeps path-based local metadata consistent with an atomic directory move, including after a crash. */
interface LibraryPathMetadata {
    suspend fun recoverPending(root: File)

    suspend fun prepareRemap(oldPrefix: String, newPrefix: String)

    suspend fun prepareRetire(prefix: String)

    suspend fun completePending()

    fun cancelPending()
}

object NoOpLibraryPathMetadata : LibraryPathMetadata {
    override suspend fun recoverPending(root: File) = Unit
    override suspend fun prepareRemap(oldPrefix: String, newPrefix: String) = Unit
    override suspend fun prepareRetire(prefix: String) = Unit
    override suspend fun completePending() = Unit
    override fun cancelPending() = Unit
}

class CrashSafeLibraryPathMetadata(
    private val pins: LocalPinStore,
    journalFile: File,
) : LibraryPathMetadata {
    private val journal = PendingPathMutationStore(journalFile)

    override suspend fun recoverPending(root: File) {
        val pending = journal.load() ?: return
        requireSafePrefix(pending.oldPrefix)
        pending.newPrefix?.let(::requireSafePrefix)
        val oldExists = existsWithoutFollowingLinks(root, pending.oldPrefix)
        val newExists = pending.newPrefix?.let { existsWithoutFollowingLinks(root, it) } ?: false
        when {
            oldExists && !newExists -> journal.clear()
            !oldExists && (pending.newPrefix == null || newExists) -> completePending()
            else -> error("A pending folder metadata remap is ambiguous; the library was left unchanged.")
        }
    }

    override suspend fun prepareRemap(oldPrefix: String, newPrefix: String) {
        requireSafePrefix(oldPrefix)
        requireSafePrefix(newPrefix)
        journal.write(PendingPathMutation(oldPrefix, newPrefix))
    }

    override suspend fun prepareRetire(prefix: String) {
        requireSafePrefix(prefix)
        journal.write(PendingPathMutation(prefix, null))
    }

    override suspend fun completePending() {
        val pending = journal.load() ?: return
        if (pending.newPrefix == null) {
            pins.retirePrefix(pending.oldPrefix)
        } else {
            pins.remapPrefix(pending.oldPrefix, pending.newPrefix)
        }
        journal.clear()
    }

    override fun cancelPending() {
        journal.clear()
    }

    private fun existsWithoutFollowingLinks(root: File, relativePath: String): Boolean {
        val target = File(root, relativePath).toPath().normalize()
        check(target.startsWith(root.toPath().normalize())) { "The pending metadata path escaped the library." }
        var current = root.toPath()
        relativePath.split('/').forEach { segment ->
            current = current.resolve(segment)
            check(!Files.isSymbolicLink(current)) { "The pending metadata path contains a symbolic link." }
        }
        return Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    }

    private fun requireSafePrefix(prefix: String) {
        require(NotePathPolicy.isFolderPath(prefix, allowRoot = false)) {
            "The pending folder metadata path is invalid."
        }
    }
}

internal data class PendingPathMutation(val oldPrefix: String, val newPrefix: String?)

internal class PendingPathMutationStore(private val file: File) {
    fun load(): PendingPathMutation? {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        check(file.isFile && !Files.isSymbolicLink(file.toPath())) { "The folder metadata journal is unsafe." }
        val fields = file.readLines(Charsets.UTF_8)
        check(fields.size == 3 && fields[0] == Header) { "The folder metadata journal is invalid." }
        val oldPrefix = decode(fields[1])
        val newPrefix = if (fields[2] == RetireMarker) null else decode(fields[2])
        return PendingPathMutation(oldPrefix, newPrefix)
    }

    fun write(pending: PendingPathMutation) {
        check(load() == null) { "Another folder metadata remap is still pending." }
        val content = buildString {
            appendLine(Header)
            appendLine(encode(pending.oldPrefix))
            appendLine(pending.newPrefix?.let(::encode) ?: RetireMarker)
        }.toByteArray(Charsets.UTF_8)
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Could not create metadata storage." } }
        val temporary = File(file.parentFile, ".miaoyan-path-${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(content)
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    fun clear() {
        if (file.exists()) check(file.delete()) { "Could not clear the completed folder metadata remap." }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String = Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)

    private companion object {
        const val Header = "MiaoYanPathMutation\t1"
        const val RetireMarker = "-"
    }
}
