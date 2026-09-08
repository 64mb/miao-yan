package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.PendingPathMutation
import com.tw93.miaoyan.android.data.PendingPathMutationStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingPathMutationStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun atomicallyPersistsRemapAndRetireOperations() {
        val file = File(temporary.root, "no-backup/path-remap.v1")
        val store = PendingPathMutationStore(file)
        val remap = PendingPathMutation("Projects", "Archive")

        store.write(remap)
        assertEquals(remap, store.load())
        store.clear()
        assertFalse(file.exists())

        val retire = PendingPathMutation("Archive", null)
        store.write(retire)
        assertEquals(retire, store.load())
    }

    @Test
    fun malformedJournalFailsClosed() {
        val file = File(temporary.root, "path-remap.v1").apply { writeText("corrupt") }

        assertTrue(runCatching { PendingPathMutationStore(file).load() }.isFailure)
    }
}
