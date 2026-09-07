package com.tw93.miaoyan.android.ui.presentation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.tw93.miaoyan.android.BuildConfig
import com.tw93.miaoyan.android.R
import com.tw93.miaoyan.android.data.EditorSettings
import com.tw93.miaoyan.android.data.EditorFont
import com.tw93.miaoyan.android.ui.PreviewNavigationPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-window host shared by continuous Preview and slide Presentation. */
@Composable
fun PresentationHost(
    mode: PresentationMode,
    markdown: String,
    editorSettings: EditorSettings,
    initialSlide: Int,
    imageHandler: PresentationImageHandler,
    onSlideChanged: (Int) -> Unit,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    if (LocalInspectionMode.current) return

    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val isMultiWindow = remember(activity, configuration) { activity?.isInMultiWindowMode == true }
    PresentationSystemBars(activity, isMultiWindow)

    var jetBrainsMonoData by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resources, editorSettings.font) {
        jetBrainsMonoData = if (editorSettings.font == EditorFont.JETBRAINS_MONO) {
            withContext(Dispatchers.IO) {
                loadJetBrainsMonoData(resources)
            }
        } else {
            null
        }
    }
    val darkMode = MaterialTheme.colorScheme.background.luminance() < .5f
    val slideCount = remember(markdown) { PresentationDocument.split(markdown).size }
    val startSlide = remember(markdown) { initialSlide.coerceIn(0, slideCount - 1) }
    val html = remember(mode, markdown, darkMode, editorSettings, jetBrainsMonoData, startSlide) {
        when (mode) {
            PresentationMode.ContinuousPreview -> PresentationDocument.renderContinuous(
                markdown,
                darkMode,
                editorSettings,
                jetBrainsMonoData,
            )
            PresentationMode.Slides -> PresentationDocument.renderSlides(
                markdown,
                darkMode,
                startSlide,
                editorSettings,
                jetBrainsMonoData,
            )
        }
    }
    val router = remember(context, imageHandler) { PresentationAssetRouter(context, imageHandler) }
    val insets = if (isMultiWindow) WindowInsets.safeDrawing else WindowInsets.displayCutout

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(insets),
    ) {
        key(mode, router) {
            PresentationWebView(
                html = html,
                javaScriptEnabled = mode == PresentationMode.Slides,
                router = router,
                maximumSlideIndex = slideCount - 1,
                onSlideChanged = onSlideChanged,
            )
        }
    }
}

@Composable
private fun PresentationSystemBars(activity: Activity?, isMultiWindow: Boolean) {
    DisposableEffect(activity, isMultiWindow) {
        val controller = activity?.window?.insetsController
        val previousBehavior = controller?.systemBarsBehavior
        if (!isMultiWindow) {
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(AndroidWindowInsets.Type.systemBars())
        }
        onDispose {
            if (!isMultiWindow) controller?.show(AndroidWindowInsets.Type.systemBars())
            if (controller != null && previousBehavior != null) controller.systemBarsBehavior = previousBehavior
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PresentationWebView(
    html: String,
    javaScriptEnabled: Boolean,
    router: PresentationAssetRouter,
    maximumSlideIndex: Int,
    onSlideChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize().testTag("presentation_webview"),
        factory = {
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isFocusable = true
                isFocusableInTouchMode = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                settings.javaScriptEnabled = javaScriptEnabled
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.domStorageEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (BuildConfig.DEBUG) {
                            Log.d(DiagnosticsTag, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                        }
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): android.webkit.WebResourceResponse? {
                        // loadDataWithBaseURL and CSP-approved inline images use local data: payloads.
                        if (request.url.scheme == "data") return null
                        return (if (request.method == "GET") router.open(request.url) else blockedResponse()).also { response ->
                            if (BuildConfig.DEBUG) Log.d(DiagnosticsTag, "Resource ${request.url}: ${response.statusCode}")
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        SlideStateNavigation.reportedIndex(uri.toString(), request.hasGesture())
                            ?.takeIf { it <= maximumSlideIndex }
                            ?.let(onSlideChanged)
                        if (
                            request.isForMainFrame && request.hasGesture() &&
                            PreviewNavigationPolicy.opensExternally(uri.toString(), userActivated = true)
                        ) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        }
                        return true
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (BuildConfig.DEBUG && request.isForMainFrame) {
                            Log.d(DiagnosticsTag, "WebView main-frame error ${error.errorCode}: ${error.description}")
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (BuildConfig.DEBUG) Log.d(DiagnosticsTag, "Page finished: $url")
                        view.requestFocus()
                    }
                }
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(PresentationDocument.AssetOrigin, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@SuppressLint("ResourceType")
private fun loadJetBrainsMonoData(resources: Resources): String =
    resources.openRawResource(R.font.jetbrains_mono_regular).use { stream ->
        Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
    }

private const val DiagnosticsTag = "MiaoYanPresentation"
