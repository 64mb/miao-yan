package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.DEFAULT_EDITOR_FONT_SIZE
import com.tw93.miaoyan.android.data.EDITOR_FONT_SIZES
import com.tw93.miaoyan.android.data.EditorFont
import com.tw93.miaoyan.android.data.normalizedEditorFontSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPreferencesTest {
    @Test
    fun fontCatalogIsSmallAndUnknownValuesFallBackToSystemSans() {
        assertEquals(4, EditorFont.entries.size)
        assertEquals(EditorFont.SYSTEM_SANS, EditorFont.fromStorage(null))
        assertEquals(EditorFont.SYSTEM_SANS, EditorFont.fromStorage("removed-font"))
        assertEquals(EditorFont.JETBRAINS_MONO, EditorFont.fromStorage("jetbrains_mono"))
    }

    @Test
    fun fontSizeCatalogHasExactlyTenFixedValuesAndSafeFallback() {
        assertEquals(10, EDITOR_FONT_SIZES.size)
        assertEquals(EDITOR_FONT_SIZES.distinct(), EDITOR_FONT_SIZES)
        assertTrue(DEFAULT_EDITOR_FONT_SIZE in EDITOR_FONT_SIZES)
        assertEquals(DEFAULT_EDITOR_FONT_SIZE, normalizedEditorFontSize(null))
        assertEquals(DEFAULT_EDITOR_FONT_SIZE, normalizedEditorFontSize(15))
        assertEquals(24, normalizedEditorFontSize(24))
    }
}
