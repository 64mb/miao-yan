package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.RestorePolicy
import com.tw93.miaoyan.android.data.TrashManifestCodec
import com.tw93.miaoyan.android.data.TrashManifestEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashManifestTest {
    @Test
    fun manifestRoundTripsUnicodeAndDelimiterCharacters() {
        val entries = listOf(
            TrashManifestEntry(
                id = "operation-2",
                originalRelativePath = "工作/plan\t一.md",
                trashRelativePath = ".Trash/items/operation-2/plan\t一.md",
                deletedAtMillis = 200,
            ),
            TrashManifestEntry(
                id = "operation-1",
                originalRelativePath = "root.md",
                trashRelativePath = ".Trash/items/operation-1/root.md",
                deletedAtMillis = 100,
            ),
        )

        assertEquals(entries.sortedBy { it.id }, TrashManifestCodec.decode(TrashManifestCodec.encode(entries)))
    }

    @Test
    fun malformedManifestFailsClosedWithoutInventingEntries() {
        assertTrue(TrashManifestCodec.decode("not-a-miaoyan-manifest").isEmpty())
        assertTrue(TrashManifestCodec.decode("MiaoYanTrashManifest\t1\ninvalid\n").isEmpty())
    }

    @Test
    fun restoreUsesOriginalFolderOrFallsBackToRoot() {
        assertEquals(
            "Projects/2026/plan.md",
            RestorePolicy.destinationRelativePath("Projects/2026/plan.md", originalParentExists = true),
        )
        assertEquals(
            "plan.md",
            RestorePolicy.destinationRelativePath("Projects/2026/plan.md", originalParentExists = false),
        )
        assertEquals("", RestorePolicy.destinationRelativePath(null, originalParentExists = false))
    }
}
