package com.tw93.miaoyan.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tw93.miaoyan.android.data.EditorPreferences
import com.tw93.miaoyan.android.data.EditorSettings
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
            val scope = rememberCoroutineScope()
            MiaoYanTheme {
                MiaoYanApp(
                    viewModel = viewModel,
                    editorSettings = editorSettings,
                    onFontChanged = { font -> scope.launch { editorPreferences.setFont(font) } },
                    onFontSizeChanged = { size -> scope.launch { editorPreferences.setFontSize(size) } },
                )
            }
        }
    }
}
