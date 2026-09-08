package com.tw93.miaoyan.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tw93.miaoyan.android.data.IndexedLibraryRepository
import com.tw93.miaoyan.android.data.LocalLibraryRepository
import com.tw93.miaoyan.android.data.LocalPinStore
import com.tw93.miaoyan.android.data.index.IndexMaintenancePolicy
import com.tw93.miaoyan.android.data.index.RoomLibrarySearchIndex
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomIndexMaintenanceInstrumentedTest {
    private val baseContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun forcedRebuildReloadsUnchangedDocumentsAndPreservesFullTextSearch() = runBlocking {
        val sandbox = File(baseContext.cacheDir, "index-rebuild-${System.nanoTime()}")
        val context = TestContext(baseContext, sandbox)
        val canonical = LocalLibraryRepository(context)
        canonical.claimForExternalInitialization()
        val root = File(context.filesDir, "libraries/default")
        val unchangedBody = "unchangedstart " + "completebody ".repeat(40_000) + " unchangedtailtoken"
        File(root, "Unchanged.md").writeText(unchangedBody)
        File(root, "Changed.md").writeText("old searchable value")

        val repository = IndexedLibraryRepository(
            canonical = canonical,
            index = RoomLibrarySearchIndex(context, alwaysRebuildPolicy()),
            pins = LocalPinStore(context),
        )
        repository.scan()
        assertEquals(listOf("Unchanged.md"), repository.search("unchangedtailtoken").map { it.relativePath })

        File(root, "Changed.md").writeText("brand new searchable value with a changed size")
        repository.scan()

        assertEquals(listOf("Unchanged.md"), repository.search("unchangedtailtoken").map { it.relativePath })
        assertEquals(listOf("Changed.md"), repository.search("brand").map { it.relativePath })
        assertTrue(context.getDatabasePath("miaoyan_search.db").length() > 0)
        sandbox.deleteRecursively()
        Unit
    }

    @Test
    fun repeatedUnchangedOpensDoNotGrowTheWalWithoutBound() = runBlocking {
        val sandbox = File(baseContext.cacheDir, "index-churn-${System.nanoTime()}")
        val context = TestContext(baseContext, sandbox)
        val canonical = LocalLibraryRepository(context)
        canonical.claimForExternalInitialization()
        val root = File(context.filesDir, "libraries/default")
        File(root, "Stable.md").writeText("stabletoken " + "body ".repeat(20_000))
        val repository = IndexedLibraryRepository(
            canonical = canonical,
            index = RoomLibrarySearchIndex(context),
            pins = LocalPinStore(context),
        )
        val note = repository.scan().single()
        val wal = File("${context.getDatabasePath("miaoyan_search.db").path}-wal")
        val before = wal.length()

        repeat(40) { repository.open(note) }

        assertEquals(listOf("Stable.md"), repository.search("stabletoken").map { it.relativePath })
        assertTrue("WAL grew from $before to ${wal.length()}", wal.length() <= before + 64 * 1024)
        sandbox.deleteRecursively()
        Unit
    }

    private fun alwaysRebuildPolicy() = IndexMaintenancePolicy(
        walCheckpointBytes = Long.MAX_VALUE,
        minimumRebuildBytes = 0,
        minimumFreeBytes = 0,
        fragmentationPercent = 0,
        proportionalSlackBytes = Long.MAX_VALUE,
        maximumSizeMultiple = 1,
    )

    private class TestContext(base: Context, private val sandbox: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(sandbox, "files").apply { mkdirs() }

        override fun getNoBackupFilesDir(): File = File(sandbox, "no-backup").apply { mkdirs() }

        override fun getCacheDir(): File = File(sandbox, "cache").apply { mkdirs() }

        override fun getDatabasePath(name: String): File = File(sandbox, "databases/$name").apply {
            parentFile?.mkdirs()
        }

        override fun deleteDatabase(name: String): Boolean {
            val path = getDatabasePath(name)
            val deleted = listOf(path, File("${path.path}-wal"), File("${path.path}-shm"))
                .map { file -> !file.exists() || file.delete() }
            return deleted.all { it }
        }
    }
}
