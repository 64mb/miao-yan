package com.tw93.miaoyan.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tw93.miaoyan.android.data.AndroidDemoSeedSource
import com.tw93.miaoyan.android.data.DemoLibraryManifest
import com.tw93.miaoyan.android.data.DemoLibrarySeeder
import com.tw93.miaoyan.android.data.IndexedLibraryRepository
import com.tw93.miaoyan.android.data.LocalLibraryRepository
import com.tw93.miaoyan.android.data.LocalPinStore
import com.tw93.miaoyan.android.data.index.RoomLibrarySearchIndex
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoLibrarySeedInstrumentedTest {
    private val baseContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun bundledAssetsAreTheExactUtf8DesktopDemoFiles() {
        val expectedHashes = mapOf(
            "Brainstorming.md" to "dd82c1f9763511d8ddacbce2b5e3070c766e3c260f0291a42111737999c2b718",
            "Introduction to MiaoYan.md" to "e2d4c5b44eb9d218d998261a064cc7c0ddaa9abd936afb8b986df774937fc758",
            "MiaoYan Markdown Syntax Guide.md" to "1f2a44bfe33b9ccf0f605a0a10b14a179c79a33b69f35afd1c7cc95d8149295f",
            "MiaoYan PPT.md" to "5e340eb179a6d4615d639059118b174016760f2e804a2bc431b6c15f43239b25",
            "Welcome.md" to "f7d94034fdd33dfe82a98828e74419271dce7c50dc15a09e8880cfe3a3360227",
            "介绍妙言.md" to "c6c5f15cca21e26a26a60378f55615432161feae86ffeb1ea4a722ee201b23f2",
            "头脑风暴.md" to "88dd38c1df253b53307baa737089b24a0f8f417f8c0561ded3ada04e6f5b2da8",
            "妙言 Markdown 语法指南.md" to "bc2491b3aea0157135b809bfb2971c7bdf8a18c172a1be5f5a67d746a0fb0782",
            "妙言 PPT.md" to "d3178375e9ad1cc8f2ca31f938176b41f34935db4ed1f0ecee42c41979947d10",
            "欢迎使用.md" to "675c31c01f2e5990b91fcd494a9fcbf1c2ae740554624382607a91b5c58a1626",
        )

        expectedHashes.forEach { (assetName, expectedHash) ->
            val bytes = baseContext.assets.open(assetName).use { it.readBytes() }
            assertEquals(expectedHash, sha256(bytes))
            assertEquals(bytes.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8).size, bytes.size)
        }
    }

    @Test
    fun firstRepositoryScanSeedsBeforeRoomIndexes() = runBlocking {
        val sandbox = File(baseContext.cacheDir, "demo-library-room-${System.nanoTime()}")
        val context = TestContext(baseContext, sandbox)
        val root = File(context.filesDir, "libraries/default")
        val seeder = DemoLibrarySeeder(
            libraryRoot = root,
            stateFile = File(context.noBackupFilesDir, "library-bootstrap/default-demo.state"),
            source = AndroidDemoSeedSource(context),
        )
        val canonical = LocalLibraryRepository(
            context,
            demoLibrarySeeder = seeder,
            preferredLanguageTags = { listOf("en-US") },
        )
        val repository = IndexedLibraryRepository(
            canonical = canonical,
            index = RoomLibrarySearchIndex(context),
            pins = LocalPinStore(context),
        )

        val firstFrameNotes = repository.scan()

        assertEquals(DemoLibraryManifest.english.map { it.relativePath }.toSet(), firstFrameNotes.map { it.relativePath }.toSet())
        assertTrue(repository.search("wonderland").any { it.relativePath == "Examples/MiaoYan Markdown Syntax Guide.md" })
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class TestContext(base: Context, private val sandbox: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(sandbox, "files").apply { mkdirs() }

        override fun getNoBackupFilesDir(): File = File(sandbox, "no-backup").apply { mkdirs() }

        override fun getCacheDir(): File = File(sandbox, "cache").apply { mkdirs() }

        override fun getDatabasePath(name: String): File = File(sandbox, "databases/$name").apply {
            parentFile?.mkdirs()
        }

        override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()
    }
}
