package com.tw93.miaoyan.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import com.tw93.miaoyan.android.ui.presentation.PresentationHost
import com.tw93.miaoyan.android.ui.presentation.PresentationImageHandler
import com.tw93.miaoyan.android.ui.presentation.PresentationMode
import com.tw93.miaoyan.android.ui.presentation.rememberPresentationSession
import com.tw93.miaoyan.android.ui.theme.MiaoYanTheme
import com.tw93.miaoyan.android.data.EditorSettings
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only host used by connected tests to exercise the real WebView and Activity recreation. */
class PresentationTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportedSlide.set(-1)
        setContent {
            MiaoYanTheme {
                val session = rememberPresentationSession()
                LaunchedEffect(Unit) {
                    if (session.mode == null) session.enter(PresentationMode.Slides)
                }
                if (session.mode == PresentationMode.Slides) {
                    PresentationHost(
                        mode = PresentationMode.Slides,
                        markdown = "# One\n---\n# Two",
                        editorSettings = EditorSettings(),
                        initialSlide = session.slide,
                        imageHandler = PresentationImageHandler.DenyAll,
                        onSlideChanged = { index ->
                            session.reportSlide(index)
                            reportedSlide.set(index)
                        },
                        onExit = session::exit,
                    )
                } else {
                    Text("Presentation closed")
                }
            }
        }
    }

    companion object {
        val reportedSlide = AtomicInteger(-1)
    }
}
