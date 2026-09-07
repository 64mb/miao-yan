package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.DemoLibraryManifest
import com.tw93.miaoyan.android.data.DemoLibrarySeeder
import com.tw93.miaoyan.android.data.DemoSeedResult
import com.tw93.miaoyan.android.data.DemoSeedSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemoLibrarySeederTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyLibrarySeedsDesktopEnglishLayoutAndContent() {
        val fixture = fixture("empty")

        assertEquals(DemoSeedResult.SEEDED, fixture.seeder.seedIfEligible(listOf("en-US")))

        assertSeedMatches(fixture, DemoLibraryManifest.english)
        assertTrue(fixture.stateFile.isFile)
        assertFalse(fixture.stateFile.toPath().startsWith(fixture.root.toPath()))
    }

    @Test
    fun completedSeedIsIdempotent() {
        val fixture = fixture("idempotent")
        assertEquals(DemoSeedResult.SEEDED, fixture.seeder.seedIfEligible(listOf("en")))
        val welcome = File(fixture.root, "Notes/Welcome.md")
        val firstModified = welcome.lastModified()

        assertEquals(DemoSeedResult.ALREADY_FINALIZED, fixture.seeder.seedIfEligible(listOf("zh-CN")))

        assertEquals(firstModified, welcome.lastModified())
        assertSeedMatches(fixture, DemoLibraryManifest.english)
        assertFalse(File(fixture.root, "Notes/欢迎使用.md").exists())
    }

    @Test
    fun anyExistingEntryPermanentlySkipsSeed() {
        val fixture = fixture("non-empty")
        File(fixture.root, "Existing.md").writeText("keep me", Charsets.UTF_8)
        File(fixture.root, ".git").mkdirs()

        assertEquals(DemoSeedResult.SKIPPED_NON_EMPTY, fixture.seeder.seedIfEligible(listOf("en")))
        assertEquals("keep me", File(fixture.root, "Existing.md").readText(Charsets.UTF_8))
        File(fixture.root, "Existing.md").delete()
        File(fixture.root, ".git").deleteRecursively()
        assertEquals(DemoSeedResult.ALREADY_FINALIZED, fixture.seeder.seedIfEligible(listOf("en")))

        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun importOrGitClaimPreventsLaterSeedEvenWhenLibraryStaysEmpty() {
        val fixture = fixture("claimed")

        fixture.seeder.claimWithoutSeeding()

        assertEquals(DemoSeedResult.ALREADY_FINALIZED, fixture.seeder.seedIfEligible(listOf("en")))
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun deletingEveryDemoNeverReseeds() {
        val fixture = fixture("deleted")
        assertEquals(DemoSeedResult.SEEDED, fixture.seeder.seedIfEligible(listOf("en")))
        fixture.root.listFiles().orEmpty().forEach(File::deleteRecursively)

        assertEquals(DemoSeedResult.ALREADY_FINALIZED, fixture.seeder.seedIfEligible(listOf("en")))

        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun firstPreferredLanguageSelectsChineseOnlyForZhLocales() {
        val chinese = fixture("chinese")
        val english = fixture("english-fallback")

        chinese.seeder.seedIfEligible(listOf("zh-Hant-TW", "en-US"))
        english.seeder.seedIfEligible(listOf("ru-RU", "zh-CN"))

        assertSeedMatches(chinese, DemoLibraryManifest.chinese)
        assertSeedMatches(english, DemoLibraryManifest.english)
        assertFalse(File(chinese.root, "Notes/Welcome.md").exists())
        assertFalse(File(english.root, "Notes/欢迎使用.md").exists())
    }

    @Test
    fun interruptedPartialSeedResumesWithoutReplacingCompletedFiles() {
        val fixture = fixture("interrupted", failOnOpen = 2)
        val failure = runCatching { fixture.seeder.seedIfEligible(listOf("zh-CN")) }.exceptionOrNull()
        assertTrue(failure is IOException)
        val first = File(fixture.root, DemoLibraryManifest.chinese.first().relativePath)
        assertTrue(first.isFile)
        val firstModified = first.lastModified()

        val resumed = DemoLibrarySeeder(fixture.root, fixture.stateFile, fixture.fullSource)
        assertEquals(DemoSeedResult.RECOVERED, resumed.seedIfEligible(listOf("en-US")))

        assertEquals(firstModified, first.lastModified())
        assertSeedMatches(fixture, DemoLibraryManifest.chinese)
        assertFalse(File(fixture.root, "Notes/Welcome.md").exists())
    }

    @Test
    fun recoveryPreservesConflictingExistingFileAndFinalizes() {
        val fixture = fixture("conflict", failOnOpen = 2)
        runCatching { fixture.seeder.seedIfEligible(listOf("en")) }
        val first = File(fixture.root, DemoLibraryManifest.english.first().relativePath)
        first.writeText("user edit", Charsets.UTF_8)

        val resumed = DemoLibrarySeeder(fixture.root, fixture.stateFile, fixture.fullSource)
        assertEquals(DemoSeedResult.ABORTED_CONFLICT, resumed.seedIfEligible(listOf("en")))
        assertEquals("user edit", first.readText(Charsets.UTF_8))
        assertEquals(DemoSeedResult.ALREADY_FINALIZED, resumed.seedIfEligible(listOf("en")))
    }

    @Test
    fun interruptedSeedAbortsIfGitOrImportClaimsLibraryBeforeRecovery() {
        val fixture = fixture("interrupted-then-claimed", failOnOpen = 2)
        runCatching { fixture.seeder.seedIfEligible(listOf("en")) }
        File(fixture.root, ".git").mkdirs()

        val resumed = DemoLibrarySeeder(fixture.root, fixture.stateFile, fixture.fullSource)
        assertEquals(DemoSeedResult.ABORTED_CONFLICT, resumed.seedIfEligible(listOf("en")))

        assertTrue(File(fixture.root, ".git").isDirectory)
        assertFalse(File(fixture.root, "Notes/Welcome.md").exists())
        assertEquals(DemoSeedResult.ALREADY_FINALIZED, resumed.seedIfEligible(listOf("en")))
    }

    private fun fixture(name: String, failOnOpen: Int? = null): Fixture {
        val base = temporaryFolder.newFolder(name)
        val root = File(base, "files/libraries/default").apply { mkdirs() }
        val stateFile = File(base, "no-backup/library-bootstrap/default-demo.state")
        val contents = (DemoLibraryManifest.english + DemoLibraryManifest.chinese)
            .associate { it.assetName to "UTF-8 demo: ${it.assetName}\n".toByteArray(Charsets.UTF_8) }
        val fullSource = DemoSeedSource { assetName ->
            ByteArrayInputStream(requireNotNull(contents[assetName]))
        }
        var opens = 0
        val possiblyFailingSource = DemoSeedSource { assetName ->
            opens += 1
            if (opens == failOnOpen) throw IOException("simulated interruption")
            fullSource.open(assetName)
        }
        return Fixture(
            root = root,
            stateFile = stateFile,
            contents = contents,
            fullSource = fullSource,
            seeder = DemoLibrarySeeder(root, stateFile, possiblyFailingSource),
        )
    }

    private fun assertSeedMatches(fixture: Fixture, entries: List<com.tw93.miaoyan.android.data.DemoSeedEntry>) {
        assertEquals(entries.map { it.relativePath }.toSet(), notePaths(fixture.root))
        entries.forEach { entry ->
            assertTrue(File(fixture.root, entry.relativePath).readBytes().contentEquals(fixture.contents[entry.assetName]))
        }
    }

    private fun notePaths(root: File): Set<String> = root.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .toSet()

    private data class Fixture(
        val root: File,
        val stateFile: File,
        val contents: Map<String, ByteArray>,
        val fullSource: DemoSeedSource,
        val seeder: DemoLibrarySeeder,
    )
}
