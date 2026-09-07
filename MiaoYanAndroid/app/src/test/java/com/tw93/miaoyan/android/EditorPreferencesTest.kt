package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.DEFAULT_EDITOR_FONT_SIZE
import com.tw93.miaoyan.android.data.EDITOR_FONT_SIZES
import com.tw93.miaoyan.android.data.EditorFont
import com.tw93.miaoyan.android.data.ThemeMode
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

    @Test
    fun themeModeStorageFallbackAndEffectiveThemeAreDeterministic() {
        assertEquals(ThemeMode.AUTO_SYSTEM, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.AUTO_SYSTEM, ThemeMode.fromStorage("removed-mode"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorage("light"))
        assertEquals(false, ThemeMode.AUTO_SYSTEM.resolveDark(false))
        assertEquals(true, ThemeMode.AUTO_SYSTEM.resolveDark(true))
        assertEquals(true, ThemeMode.DARK.resolveDark(false))
        assertEquals(false, ThemeMode.LIGHT.resolveDark(true))
    }
}
