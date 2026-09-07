package com.tw93.miaoyan.android

import android.app.LocaleManager
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.tw93.miaoyan.android.data.EditorPreferences
import com.tw93.miaoyan.android.data.EditorSettings
import com.tw93.miaoyan.android.data.AppLanguage
import com.tw93.miaoyan.android.ui.LibraryViewModel
import com.tw93.miaoyan.android.ui.MiaoYanApp
import com.tw93.miaoyan.android.ui.theme.MiaoYanTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels()
    private val editorPreferences by lazy { EditorPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val editorSettings by editorPreferences.settings.collectAsStateWithLifecycle(EditorSettings())
            val localeManager = getSystemService(LocaleManager::class.java)
            val appLanguage = AppLanguage.fromLanguageTags(localeManager.applicationLocales.toLanguageTags())
            val scope = rememberCoroutineScope()
            val effectiveDarkTheme = editorSettings.themeMode.resolveDark(isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !effectiveDarkTheme
                    isAppearanceLightNavigationBars = !effectiveDarkTheme
                }
            }
            MiaoYanTheme(themeMode = editorSettings.themeMode) {
                MiaoYanApp(
                    viewModel = viewModel,
                    editorSettings = editorSettings,
                    appLanguage = appLanguage,
                    onFontChanged = { font -> scope.launch { editorPreferences.setFont(font) } },
                    onFontSizeChanged = { size -> scope.launch { editorPreferences.setFontSize(size) } },
                    onThemeModeChanged = { mode -> scope.launch { editorPreferences.setThemeMode(mode) } },
                    onLanguageChanged = { language ->
                        val requested = LocaleList.forLanguageTags(language.languageTag)
                        if (localeManager.applicationLocales.toLanguageTags() != requested.toLanguageTags()) {
                            localeManager.applicationLocales = requested
                        }
                    },
                )
            }
        }
    }
}
