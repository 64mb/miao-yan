package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.ui.LibraryWindowLayout
import com.tw93.miaoyan.android.ui.LibraryWindowLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryWindowLayoutTest {
    @Test
    fun switchesToListDetailAtTabletBreakpoint() {
        assertEquals(LibraryWindowLayoutMode.SinglePane, LibraryWindowLayout.forWidthDp(839))
        assertEquals(LibraryWindowLayoutMode.ListDetail, LibraryWindowLayout.forWidthDp(840))
        assertEquals(LibraryWindowLayoutMode.ListDetail, LibraryWindowLayout.forWidthDp(1_200))
    }
}
