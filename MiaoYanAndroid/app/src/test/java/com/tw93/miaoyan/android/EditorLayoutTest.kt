package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.ui.editorPaddingPixels
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorLayoutTest {
    @Test
    fun editorPaddingUsesDensityIndependentDimensions() {
        assertEquals(20, editorPaddingPixels(1f).horizontal)
        assertEquals(18, editorPaddingPixels(1f).top)
        assertEquals(48, editorPaddingPixels(1f).bottom)

        val emulatorPadding = editorPaddingPixels(2.625f)
        assertEquals(53, emulatorPadding.horizontal)
        assertEquals(47, emulatorPadding.top)
        assertEquals(126, emulatorPadding.bottom)
    }
}
