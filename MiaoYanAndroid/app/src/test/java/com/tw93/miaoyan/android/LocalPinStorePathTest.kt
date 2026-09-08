package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.remapPinnedPrefix
import com.tw93.miaoyan.android.data.retirePinnedPrefix
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPinStorePathTest {
    @Test
    fun folderRenameRemapsOnlyDescendantPins() {
        val pins = setOf("Work/Plan.md", "Work/Nested/Idea.md", "Workshop/Keep.md", "Root.md")

        assertEquals(
            setOf("Archive/Plan.md", "Archive/Nested/Idea.md", "Workshop/Keep.md", "Root.md"),
            pins.remapPinnedPrefix("Work", "Archive"),
        )
    }

    @Test
    fun folderTrashRetiresOnlyDescendantPins() {
        val pins = setOf("Work/Plan.md", "Work/Nested/Idea.md", "Workshop/Keep.md", "Root.md")

        assertEquals(
            setOf("Workshop/Keep.md", "Root.md"),
            pins.retirePinnedPrefix("Work"),
        )
    }
}
