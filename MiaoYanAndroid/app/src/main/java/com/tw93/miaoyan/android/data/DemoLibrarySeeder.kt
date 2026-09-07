package com.tw93.miaoyan.android.data

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

fun interface DemoSeedSource {
    fun open(assetName: String): InputStream
}

enum class DemoSeedResult {
    SEEDED,
    RECOVERED,
    ALREADY_FINALIZED,
    SKIPPED_NON_EMPTY,
    ABORTED_CONFLICT,
}

data class DemoSeedEntry(
    val assetName: String,
    val relativePath: String,
)

/** The filenames and folder distribution mirror Business/Storage.swift exactly. */
object DemoLibraryManifest {
    const val SchemaVersion = 1

    val english = listOf(
        DemoSeedEntry("Introduction to MiaoYan.md", "Guide/Introduction to MiaoYan.md"),
        DemoSeedEntry("MiaoYan PPT.md", "Examples/MiaoYan PPT.md"),
        DemoSeedEntry("MiaoYan Markdown Syntax Guide.md", "Examples/MiaoYan Markdown Syntax Guide.md"),
        DemoSeedEntry("Welcome.md", "Notes/Welcome.md"),
        DemoSeedEntry("Brainstorming.md", "Ideas/Brainstorming.md"),
    )

    val chinese = listOf(
        DemoSeedEntry("介绍妙言.md", "Guide/介绍妙言.md"),
        DemoSeedEntry("妙言 PPT.md", "Examples/妙言 PPT.md"),
        DemoSeedEntry("妙言 Markdown 语法指南.md", "Examples/妙言 Markdown 语法指南.md"),
        DemoSeedEntry("欢迎使用.md", "Notes/欢迎使用.md"),
        DemoSeedEntry("头脑风暴.md", "Ideas/头脑风暴.md"),
    )

    fun localeKey(preferredLanguageTags: List<String>): String =
        if (preferredLanguageTags.firstOrNull()?.trim()?.startsWith("zh", ignoreCase = true) == true) "zh" else "en"

    fun entries(localeKey: String): List<DemoSeedEntry> = if (localeKey == "zh") chinese else english
}

/**
 * Seeds a genuinely empty app-private library once.
 *
 * The state file and staging files live outside the canonical library/Git working tree. A durable
 * in-progress state is written before the first note, so a process death resumes missing files
 * without replacing any existing path. Every terminal state permanently disables future seeding.
 */
class DemoLibrarySeeder(
    private val libraryRoot: File,
    private val stateFile: File,
    private val source: DemoSeedSource,
) {
    fun seedIfEligible(preferredLanguageTags: List<String>): DemoSeedResult {
        return when (val state = readState()) {
            null -> beginIfEmpty(preferredLanguageTags)
            is SeedState.InProgress -> recover(state.localeKey)
            SeedState.Finalized, SeedState.Unknown -> DemoSeedResult.ALREADY_FINALIZED
        }
    }

    /** Call before Import or future Git initialization that can claim an empty library. */
    fun claimWithoutSeeding() {
        if (readState() !is SeedState.Finalized) {
            writeState(status = FinishedStatus, outcome = ClaimedOutcome)
        }
    }

    private fun beginIfEmpty(preferredLanguageTags: List<String>): DemoSeedResult {
        ensureLibraryRoot()
        if (libraryRoot.listFiles()?.isNotEmpty() != false) {
            writeState(status = FinishedStatus, outcome = ExistingOutcome)
            return DemoSeedResult.SKIPPED_NON_EMPTY
        }

        val localeKey = DemoLibraryManifest.localeKey(preferredLanguageTags)
        writeState(status = InProgressStatus, localeKey = localeKey)
        return seed(localeKey, recovering = false)
    }

    private fun recover(localeKey: String): DemoSeedResult {
        if (hasUnexpectedRecoveryEntry(localeKey)) {
            writeState(status = FinishedStatus, outcome = ConflictOutcome, localeKey = localeKey)
            return DemoSeedResult.ABORTED_CONFLICT
        }
        return seed(localeKey, recovering = true)
    }

    private fun hasUnexpectedRecoveryEntry(localeKey: String): Boolean {
        ensureLibraryRoot()
        val expectedFiles = DemoLibraryManifest.entries(localeKey).mapTo(mutableSetOf(), DemoSeedEntry::relativePath)
        val expectedDirectories = expectedFiles.mapTo(mutableSetOf()) { it.substringBeforeLast('/') }
        val pending = ArrayDeque<File>()
        pending.add(libraryRoot)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            for (child in directory.listFiles() ?: return true) {
                if (Files.isSymbolicLink(child.toPath())) return true
                val relativePath = child.relativeTo(libraryRoot).invariantSeparatorsPath
                when {
                    child.isDirectory && relativePath in expectedDirectories -> pending.add(child)
                    child.isFile && relativePath in expectedFiles -> Unit
                    else -> return true
                }
            }
        }
        return false
    }

    private fun seed(localeKey: String, recovering: Boolean): DemoSeedResult {
        ensureLibraryRoot()
        return try {
            for (entry in DemoLibraryManifest.entries(localeKey)) {
                seedEntry(entry)
            }
            writeState(status = FinishedStatus, outcome = SeededOutcome, localeKey = localeKey)
            if (recovering) DemoSeedResult.RECOVERED else DemoSeedResult.SEEDED
        } catch (_: SeedConflictException) {
            writeState(status = FinishedStatus, outcome = ConflictOutcome, localeKey = localeKey)
            DemoSeedResult.ABORTED_CONFLICT
        }
    }

    private fun seedEntry(entry: DemoSeedEntry) {
        val bytes = source.open(entry.assetName).use(InputStream::readBytes)
        val target = resolveSeedPath(entry.relativePath)
        val parent = ensureSeedParent(target.parentFile ?: throw SeedConflictException())
        if (target.exists() || Files.isSymbolicLink(target.toPath())) {
            val isRegularFile = Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)
            if (!isRegularFile || target.length() != bytes.size.toLong() || !target.readBytes().contentEquals(bytes)) {
                throw SeedConflictException()
            }
            return
        }
        atomicCreate(parent, target, bytes)
    }

    private fun ensureLibraryRoot() {
        check(
            (libraryRoot.isDirectory || libraryRoot.mkdirs()) &&
                !Files.isSymbolicLink(libraryRoot.toPath()),
        ) { "The private MiaoYan library is unavailable." }
    }

    private fun resolveSeedPath(relativePath: String): File {
        check(NotePathPolicy.isNote(relativePath)) { "The bundled demo path is invalid." }
        val rootPath = libraryRoot.toPath().toAbsolutePath().normalize()
        val target = rootPath.resolve(relativePath).normalize()
        check(target.startsWith(rootPath)) { "The bundled demo path escaped the private library." }
        return target.toFile()
    }

    private fun ensureSeedParent(parent: File): File {
        val rootPath = libraryRoot.toPath().toAbsolutePath().normalize()
        val parentPath = parent.toPath().toAbsolutePath().normalize()
        if (!parentPath.startsWith(rootPath)) throw SeedConflictException()

        var current = libraryRoot
        for (segment in rootPath.relativize(parentPath)) {
            current = File(current, segment.toString())
            if (current.exists() || Files.isSymbolicLink(current.toPath())) {
                if (!current.isDirectory || Files.isSymbolicLink(current.toPath())) throw SeedConflictException()
            } else if (!current.mkdir()) {
                throw IllegalStateException("Could not create a demo-library folder.")
            }
        }
        return current
    }

    private fun atomicCreate(parent: File, target: File, bytes: ByteArray) {
        val stagingDirectory = File(stateFile.parentFile, StagingDirectoryName)
        check(stagingDirectory.isDirectory || stagingDirectory.mkdirs()) { "Could not create demo-library staging." }
        val temporary = File(stagingDirectory, "${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (target.exists() || Files.isSymbolicLink(target.toPath())) throw SeedConflictException()
            check(target.parentFile == parent) { "The demo-library target changed unexpectedly." }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    private fun readState(): SeedState? {
        if (!stateFile.exists()) return null
        if (!stateFile.isFile) return SeedState.Unknown
        val values = try {
            stateFile.readLines(Charsets.UTF_8)
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
        } catch (_: Throwable) {
            return SeedState.Unknown
        }
        if (values[SchemaKey] != DemoLibraryManifest.SchemaVersion.toString()) return SeedState.Unknown
        return when (values[StatusKey]) {
            InProgressStatus -> when (val localeKey = values[LocaleKey]) {
                "en", "zh" -> SeedState.InProgress(localeKey)
                else -> SeedState.Unknown
            }
            FinishedStatus -> SeedState.Finalized
            else -> SeedState.Unknown
        }
    }

    private fun writeState(status: String, outcome: String? = null, localeKey: String? = null) {
        val parent = stateFile.parentFile ?: error("The demo-library state path has no parent.")
        check(parent.isDirectory || parent.mkdirs()) { "Could not create demo-library metadata." }
        val content = buildString {
            append("$SchemaKey=${DemoLibraryManifest.SchemaVersion}\n")
            append("$StatusKey=$status\n")
            outcome?.let { append("$OutcomeKey=$it\n") }
            localeKey?.let { append("$LocaleKey=$it\n") }
        }.toByteArray(Charsets.UTF_8)
        val temporary = File(parent, ".${stateFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                stateFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private sealed interface SeedState {
        data class InProgress(val localeKey: String) : SeedState
        data object Finalized : SeedState
        data object Unknown : SeedState
    }

    private class SeedConflictException : Exception()

    private companion object {
        const val SchemaKey = "schema"
        const val StatusKey = "status"
        const val OutcomeKey = "outcome"
        const val LocaleKey = "locale"
        const val InProgressStatus = "in-progress"
        const val FinishedStatus = "finished"
        const val SeededOutcome = "seeded"
        const val ExistingOutcome = "existing-library"
        const val ClaimedOutcome = "claimed-without-seed"
        const val ConflictOutcome = "conflict-preserved"
        const val StagingDirectoryName = "staging"
    }
}
