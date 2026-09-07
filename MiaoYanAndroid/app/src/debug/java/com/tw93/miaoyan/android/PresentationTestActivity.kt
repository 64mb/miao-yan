package com.tw93.miaoyan.android

import android.os.Bundle
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.tw93.miaoyan.android.data.EditorFont
import com.tw93.miaoyan.android.data.EditorSettings
import com.tw93.miaoyan.android.ui.presentation.ContinuousPreview
import com.tw93.miaoyan.android.ui.presentation.PresentationHost
import com.tw93.miaoyan.android.ui.presentation.PresentationImageHandler
import com.tw93.miaoyan.android.ui.presentation.PresentationMode
import com.tw93.miaoyan.android.ui.presentation.PreviewWebViewController
import com.tw93.miaoyan.android.ui.presentation.rememberContinuousPreviewDocument
import com.tw93.miaoyan.android.ui.presentation.rememberPresentationSession
import com.tw93.miaoyan.android.ui.theme.MiaoYanTheme
import java.io.ByteArrayInputStream
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

/** Debug-only retained continuous-preview host used to verify preload, reuse, and renderer recovery. */
class ContinuousPreviewTestActivity : ComponentActivity() {
    private lateinit var active: MutableState<Boolean>
    private lateinit var fullscreen: MutableState<Boolean>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiaoYanTheme {
                active = remember { mutableStateOf(false) }
                fullscreen = remember { mutableStateOf(false) }
                val preparation = rememberContinuousPreviewDocument(
                    markdown = TestMarkdown,
                    darkMode = true,
                    editorSettings = EditorSettings(EditorFont.JETBRAINS_MONO, 16),
                    enabled = true,
                )
                val controller = remember {
                    PreviewWebViewController().also { previewController = it }
                }
                Box(Modifier.fillMaxSize()) {
                    ContinuousPreview(
                        preparation = preparation,
                        controller = controller,
                        imageHandler = TestImageHandler,
                        active = active.value,
                        fullscreen = fullscreen.value,
                        onExitFullscreen = { fullscreen.value = false },
                    )
                }
            }
        }
    }

    fun showInlinePreview() {
        previewController.markRequested("instrumented-inline")
        active.value = true
    }

    fun setFullscreen(enabled: Boolean) {
        previewController.markRequested("instrumented-fullscreen")
        fullscreen.value = enabled
    }

    companion object {
        internal lateinit var previewController: PreviewWebViewController

        private val TestPng = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            Base64.DEFAULT,
        )
        private val TestImageHandler = PresentationImageHandler {
            WebResourceResponse("image/png", null, ByteArrayInputStream(TestPng))
        }
        private val TestMarkdown = buildString {
            appendLine("# Retained preview")
            appendLine("![](/i/test.png)")
            repeat(120) { appendLine("Paragraph $it keeps this document scrollable.") }
        }
    }
}
