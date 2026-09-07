package com.tw93.miaoyan.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.tw93.miaoyan.android.ui.LibraryViewModel
import com.tw93.miaoyan.android.ui.MiaoYanApp
import com.tw93.miaoyan.android.ui.theme.MiaoYanTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiaoYanTheme {
                MiaoYanApp(viewModel)
            }
        }
    }
}
