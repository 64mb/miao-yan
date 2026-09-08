package com.tw93.miaoyan.android.typesetting

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledPrettierPageLoaderTest {
    @Test
    fun lazilyReadsOnlyRuntimeScriptsOffMainAndCachesThePage() {
        val reads = mutableListOf<Pair<String, String>>()
        Executors.newSingleThreadExecutor { task -> Thread(task, "prettier-asset-io") }
            .asCoroutineDispatcher().use { dispatcher ->
                val loader = BundledPrettierPageLoader(
                    readAsset = { path ->
                        reads += path to Thread.currentThread().name
                        "// $path"
                    },
                    ioDispatcher = dispatcher,
                )

                assertTrue(reads.isEmpty())
                val pages = runBlocking { loader.load() to loader.load() }

                assertEquals(pages.first, pages.second)
                assertEquals(
                    listOf("prettier/standalone.js", "prettier/markdown.js"),
                    reads.map { it.first },
                )
                assertTrue(reads.all { it.second.startsWith("prettier-asset-io") })
                assertFalse(reads.any { it.first.contains("LICENSE") || it.first.contains("NOTICE") })
            }
    }
}
