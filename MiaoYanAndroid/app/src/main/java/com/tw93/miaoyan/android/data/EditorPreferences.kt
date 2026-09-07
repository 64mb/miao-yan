package com.tw93.miaoyan.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.editorDataStore by preferencesDataStore(name = "miaoyan_editor")

enum class EditorFont(val storageValue: String, val cssStack: String) {
    SYSTEM_SANS("system_sans", "system-ui, \"Noto Sans\", Roboto, sans-serif"),
    SYSTEM_SERIF("system_serif", "serif"),
    SYSTEM_MONOSPACE("system_monospace", "ui-monospace, monospace"),
    JETBRAINS_MONO("jetbrains_mono", "\"JetBrains Mono\", ui-monospace, monospace"),
    ;

    companion object {
        fun fromStorage(value: String?): EditorFont = entries.firstOrNull { it.storageValue == value } ?: SYSTEM_SANS
    }
}

enum class ThemeMode(val storageValue: String) {
    AUTO_SYSTEM("auto_system"),
    DARK("dark"),
    LIGHT("light"),
    ;

    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        AUTO_SYSTEM -> systemDark
        DARK -> true
        LIGHT -> false
    }

    companion object {
        fun fromStorage(value: String?): ThemeMode = entries.firstOrNull { it.storageValue == value } ?: AUTO_SYSTEM
    }
}

data class EditorSettings(
    val font: EditorFont = EditorFont.SYSTEM_SANS,
    val fontSizeSp: Int = DEFAULT_EDITOR_FONT_SIZE,
    val themeMode: ThemeMode = ThemeMode.AUTO_SYSTEM,
)

const val DEFAULT_EDITOR_FONT_SIZE = 16

// Keep the selector deliberately short and deterministic across devices.
val EDITOR_FONT_SIZES = listOf(12, 14, 16, 17, 18, 20, 22, 24, 28, 32)

fun normalizedEditorFontSize(value: Int?): Int = value?.takeIf(EDITOR_FONT_SIZES::contains) ?: DEFAULT_EDITOR_FONT_SIZE

class EditorPreferences(private val context: Context) {
    val settings: Flow<EditorSettings> = context.editorDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            EditorSettings(
                font = EditorFont.fromStorage(preferences[FontKey]),
                fontSizeSp = normalizedEditorFontSize(preferences[FontSizeKey]),
                themeMode = ThemeMode.fromStorage(preferences[ThemeModeKey]),
            )
        }

    suspend fun setFont(font: EditorFont) {
        context.editorDataStore.edit { preferences -> preferences[FontKey] = font.storageValue }
    }

    suspend fun setFontSize(fontSizeSp: Int) {
        require(fontSizeSp in EDITOR_FONT_SIZES)
        context.editorDataStore.edit { preferences -> preferences[FontSizeKey] = fontSizeSp }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        context.editorDataStore.edit { preferences -> preferences[ThemeModeKey] = themeMode.storageValue }
    }

    private companion object {
        val FontKey = stringPreferencesKey("editor_font")
        val FontSizeKey = intPreferencesKey("editor_font_size_sp")
        val ThemeModeKey = stringPreferencesKey("theme_mode")
    }
}
