package com.tw93.miaoyan.android.ui.presentation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.tw93.miaoyan.android.BuildConfig
import com.tw93.miaoyan.android.R
import com.tw93.miaoyan.android.data.EditorSettings
import com.tw93.miaoyan.android.ui.PreviewNavigationPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class ContinuousPreviewRequest(
    val markdown: String,
    val darkMode: Boolean,
    val editorSettings: EditorSettings,
)

internal data class PreparedContinuousPreview(
    val request: ContinuousPreviewRequest,
    val html: String,
    val preparationMillis: Long,
)

internal data class ContinuousPreviewPreparation(
    val request: ContinuousPreviewRequest,
    val document: PreparedContinuousPreview?,
)

/** Prepares the exact document used by embedded and fullscreen continuous preview off the UI thread. */
@Composable
internal fun rememberContinuousPreviewDocument(
    markdown: String,
    darkMode: Boolean,
    editorSettings: EditorSettings,
    enabled: Boolean,
): ContinuousPreviewPreparation {
    val request = remember(markdown, darkMode, editorSettings) {
        ContinuousPreviewRequest(markdown, darkMode, editorSettings)
    }
    var prepared by remember { mutableStateOf<PreparedContinuousPreview?>(null) }
    LaunchedEffect(request, enabled) {
        if (!enabled) return@LaunchedEffect
        delay(PreviewPreparationDebounceMillis)
        val startedAt = SystemClock.elapsedRealtime()
        val html = withContext(Dispatchers.Default) {
            PresentationDocument.renderContinuous(markdown, darkMode, editorSettings)
        }
        val duration = SystemClock.elapsedRealtime() - startedAt
        prepared = PreparedContinuousPreview(request, html, duration)
        if (BuildConfig.DEBUG) {
            Log.d(DiagnosticsTag, "continuous prepared=${duration}ms chars=${markdown.length} html=${html.length}")
        }
    }
    return ContinuousPreviewPreparation(request, prepared?.takeIf { it.request == request })
}

internal class PreviewWebViewController {
    var generation by mutableIntStateOf(0)
        private set
    var readyRevision by mutableStateOf<Any?>(null)
        private set
    var awaitingManualRetry by mutableStateOf(false)
        private set
    var failureDescription by mutableStateOf<String?>(null)
        private set
    var htmlLoadCount by mutableIntStateOf(0)
        private set
    var fontRequestCount by mutableIntStateOf(0)
        private set
    var imageRequestCount by mutableIntStateOf(0)
        private set
    var pageFinishedCount by mutableIntStateOf(0)
        private set
    var firstVisibleFrameCount by mutableIntStateOf(0)
        private set

    private var requestStartedAt = 0L
    private var requestedSurface = "prefetch"
    private var renderGoneCount = 0

    fun markRequested(surface: String) {
        requestStartedAt = SystemClock.elapsedRealtime()
        requestedSurface = surface
        if (BuildConfig.DEBUG) Log.d(DiagnosticsTag, "$surface requested")
    }

    fun onHtmlLoad(htmlLength: Int, preparationMillis: Long) {
        readyRevision = null
        failureDescription = null
        htmlLoadCount += 1
        if (BuildConfig.DEBUG) {
            Log.d(
                DiagnosticsTag,
                "html load #$htmlLoadCount surface=$requestedSurface prepare=${preparationMillis}ms html=$htmlLength",
            )
        }
    }

    fun onResourceOpened(path: String) {
        if (path == BundledFontPath) fontRequestCount += 1
        if (path.startsWith("/i/")) imageRequestCount += 1
        if (BuildConfig.DEBUG && path != "/favicon.ico") Log.d(DiagnosticsTag, "resource $path")
    }

    fun onPageCommitVisible(revision: Any?) {
        if (BuildConfig.DEBUG && revision != null) {
            Log.d(DiagnosticsTag, "page commit visible surface=$requestedSurface elapsed=${requestElapsed()}ms")
        }
    }

    fun onPageFinished(revision: Any?, url: String?) {
        val isPreviewDocument = url?.let {
            it.startsWith("data:text/html") || it.startsWith(PresentationDocument.AssetOrigin)
        } == true
        if (revision == null || !isPreviewDocument) return
        readyRevision = revision
        failureDescription = null
        awaitingManualRetry = false
        renderGoneCount = 0
        pageFinishedCount += 1
        if (BuildConfig.DEBUG) {
            Log.d(
                DiagnosticsTag,
                "page finished #$pageFinishedCount surface=$requestedSurface elapsed=${requestElapsed()}ms",
            )
        }
    }

    fun onMainFrameError(code: Int, description: CharSequence?) {
        readyRevision = null
        failureDescription = "$code: ${description?.toString().orEmpty()}"
        awaitingManualRetry = true
        if (BuildConfig.DEBUG) Log.e(DiagnosticsTag, "main-frame error $failureDescription")
    }

    fun onRenderProcessGone(detail: RenderProcessGoneDetail, expectedRelease: Boolean) {
        if (BuildConfig.DEBUG) {
            Log.e(
                DiagnosticsTag,
                "render process gone didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()} " +
                    "expectedRelease=$expectedRelease",
            )
        }
        if (expectedRelease) return
        readyRevision = null
        renderGoneCount += 1
        failureDescription = "renderer gone (crash=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()})"
        awaitingManualRetry = renderGoneCount > AutomaticRendererRestarts
        generation += 1
    }

    fun retry() {
        renderGoneCount = 0
        awaitingManualRetry = false
        failureDescription = null
        readyRevision = null
        generation += 1
        markRequested("retry")
    }

    fun onFirstVisibleFrame() {
        firstVisibleFrameCount += 1
        if (BuildConfig.DEBUG) {
            Log.d(DiagnosticsTag, "first visible frame surface=$requestedSurface elapsed=${requestElapsed()}ms")
        }
    }

    private fun requestElapsed(): Long = requestStartedAt.takeIf { it > 0L }
        ?.let { SystemClock.elapsedRealtime() - it }
        ?: 0L
}

/** One retained WebView surface shared by Edit/Preview and fullscreen continuous layout. */
@Composable
internal fun ContinuousPreview(
    preparation: ContinuousPreviewPreparation,
    controller: PreviewWebViewController,
    imageHandler: PresentationImageHandler,
    active: Boolean,
    fullscreen: Boolean,
    onExitFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) return
    BackHandler(enabled = active && fullscreen, onBack = onExitFullscreen)
    FullscreenSystemBars(enabled = active && fullscreen)

    val context = LocalContext.current
    val router = remember(context, imageHandler, controller) {
        PresentationAssetRouter(context, imageHandler, controller::onResourceOpened)
    }
    val document = preparation.document
    val ready = document != null && controller.readyRevision == document.request
    val insetsModifier = if (fullscreen) {
        val activity = remember(context) { context.findActivity() }
        val configuration = LocalConfiguration.current
        val isMultiWindow = remember(activity, configuration) { activity?.isInMultiWindowMode == true }
        Modifier.windowInsetsPadding(if (isMultiWindow) WindowInsets.safeDrawing else WindowInsets.displayCutout)
    } else {
        Modifier
    }

    val backgroundModifier = if (active) {
        Modifier.background(MaterialTheme.colorScheme.background)
    } else {
        Modifier
    }
    Box(
        modifier.fillMaxSize().then(insetsModifier).then(backgroundModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (document != null && !controller.awaitingManualRetry) {
            key(controller.generation, router) {
                SecureDocumentWebView(
                    document = WebPreviewDocument(
                        revision = document.request,
                        html = document.html,
                        javaScriptEnabled = false,
                        maximumSlideIndex = 0,
                        preparationMillis = document.preparationMillis,
                    ),
                    router = router,
                    controller = controller,
                    active = active && ready,
                    onSlideChanged = {},
                    modifier = Modifier.fillMaxSize().alpha(if (active && ready) 1f else 0f)
                        .testTag("continuous_preview_webview"),
                )
            }
        }
        if (active && !ready && !controller.awaitingManualRetry) {
            CircularProgressIndicator(Modifier.testTag("preview_skeleton"))
        }
        if (active && controller.awaitingManualRetry) {
            TextButton(onClick = controller::retry, modifier = Modifier.testTag("preview_retry")) {
                Text("${stringResource(R.string.preview_renderer_failed)} ${stringResource(R.string.retry)}")
            }
        }
    }

    LaunchedEffect(active, ready, document?.request) {
        if (active && ready) withFrameNanos { controller.onFirstVisibleFrame() }
    }
}

/** Full-window Reveal host. Continuous preview is retained by [ContinuousPreview] instead. */
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
    require(mode == PresentationMode.Slides) { "Continuous preview must use the retained continuous host." }
    BackHandler(onBack = onExit)
    if (LocalInspectionMode.current) return
    FullscreenSystemBars(enabled = true)

    val context = LocalContext.current
    val darkMode = MaterialTheme.colorScheme.background.luminance() < .5f
    val request = remember(markdown, darkMode, editorSettings, initialSlide) {
        SlidesRequest(markdown, darkMode, editorSettings, initialSlide)
    }
    var prepared by remember { mutableStateOf<PreparedSlides?>(null) }
    val controller = remember { PreviewWebViewController().also { it.markRequested("slides") } }
    LaunchedEffect(request) {
        val startedAt = SystemClock.elapsedRealtime()
        val result = withContext(Dispatchers.Default) {
            val count = PresentationDocument.split(markdown).size
            val start = initialSlide.coerceIn(0, count - 1)
            PreparedSlides(
                request,
                PresentationDocument.renderSlides(markdown, darkMode, start, editorSettings),
                count - 1,
                0L,
            )
        }
        prepared = result.copy(preparationMillis = SystemClock.elapsedRealtime() - startedAt)
    }
    val current = prepared?.takeIf { it.request == request }
    val router = remember(context, imageHandler, controller) {
        PresentationAssetRouter(context, imageHandler, controller::onResourceOpened)
    }
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val isMultiWindow = remember(activity, configuration) { activity?.isInMultiWindowMode == true }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(if (isMultiWindow) WindowInsets.safeDrawing else WindowInsets.displayCutout),
        contentAlignment = Alignment.Center,
    ) {
        if (current == null) {
            CircularProgressIndicator(Modifier.testTag("preview_skeleton"))
        } else if (!controller.awaitingManualRetry) {
            key(controller.generation, router) {
                SecureDocumentWebView(
                    document = WebPreviewDocument(
                        current.request,
                        current.html,
                        javaScriptEnabled = true,
                        maximumSlideIndex = current.maximumSlideIndex,
                        preparationMillis = current.preparationMillis,
                    ),
                    router = router,
                    controller = controller,
                    active = controller.readyRevision == current.request,
                    onSlideChanged = onSlideChanged,
                    modifier = Modifier.fillMaxSize().alpha(
                        if (controller.readyRevision == current.request) 1f else 0f,
                    ).testTag("presentation_webview"),
                )
            }
            if (controller.readyRevision != current.request) {
                CircularProgressIndicator(Modifier.testTag("preview_skeleton"))
            }
        } else {
            TextButton(onClick = controller::retry, modifier = Modifier.testTag("preview_retry")) {
                Text("${stringResource(R.string.preview_renderer_failed)} ${stringResource(R.string.retry)}")
            }
        }
    }
}

@Composable
private fun FullscreenSystemBars(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val isMultiWindow = remember(activity, configuration) { activity?.isInMultiWindowMode == true }
    DisposableEffect(activity, enabled, isMultiWindow) {
        val controller = activity?.window?.insetsController
        val previousBehavior = controller?.systemBarsBehavior
        if (enabled && !isMultiWindow) {
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(AndroidWindowInsets.Type.systemBars())
        }
        onDispose {
            if (enabled && !isMultiWindow) controller?.show(AndroidWindowInsets.Type.systemBars())
            if (controller != null && previousBehavior != null) controller.systemBarsBehavior = previousBehavior
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SecureDocumentWebView(
    document: WebPreviewDocument,
    router: PresentationAssetRouter,
    controller: PreviewWebViewController,
    active: Boolean,
    onSlideChanged: (Int) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = {
            val session = PreviewWebViewSession(router, controller, onSlideChanged)
            WebView(context).apply {
                tag = session
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isFocusable = true
                isFocusableInTouchMode = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                settings.javaScriptEnabled = document.javaScriptEnabled
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
                        if (request.url.scheme == "data") return null
                        val currentSession = view.tag as PreviewWebViewSession
                        return if (request.method == "GET") {
                            currentSession.router.open(request.url)
                        } else {
                            blockedResponse()
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val currentSession = view.tag as PreviewWebViewSession
                        val uri = request.url
                        SlideStateNavigation.reportedIndex(uri.toString(), request.hasGesture())
                            ?.takeIf { it <= currentSession.maximumSlideIndex }
                            ?.let(currentSession.onSlideChanged)
                        if (
                            request.isForMainFrame && request.hasGesture() &&
                            currentSession.router.openAttachment(context, uri.toString())
                        ) {
                            return true
                        }
                        if (
                            request.isForMainFrame && request.hasGesture() &&
                            PreviewNavigationPolicy.opensExternally(uri.toString(), userActivated = true)
                        ) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        }
                        return true
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            (view.tag as PreviewWebViewSession).controller.onMainFrameError(
                                error.errorCode,
                                error.description,
                            )
                        }
                    }

                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        val currentSession = view.tag as PreviewWebViewSession
                        currentSession.controller.onPageCommitVisible(currentSession.revision)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        val currentSession = view.tag as PreviewWebViewSession
                        currentSession.controller.onPageFinished(currentSession.revision, url)
                        if (currentSession.active) view.requestFocus()
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        val currentSession = view.tag as PreviewWebViewSession
                        currentSession.controller.onRenderProcessGone(detail, currentSession.expectedRelease)
                        return true
                    }
                }
            }
        },
        update = { webView ->
            val session = webView.tag as PreviewWebViewSession
            session.router = router
            session.onSlideChanged = onSlideChanged
            session.maximumSlideIndex = document.maximumSlideIndex
            session.active = active
            webView.visibility = if (active) View.VISIBLE else View.INVISIBLE
            if (session.revision != document.revision) {
                session.revision = document.revision
                session.expectedRelease = false
                webView.settings.javaScriptEnabled = document.javaScriptEnabled
                controller.onHtmlLoad(document.html.length, document.preparationMillis)
                webView.loadDataWithBaseURL(
                    PresentationDocument.AssetOrigin,
                    document.html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        onRelease = { webView ->
            val session = webView.tag as? PreviewWebViewSession
            if (session != null) session.expectedRelease = true
            if (BuildConfig.DEBUG) Log.d(DiagnosticsTag, "WebView release: expected destroy")
            webView.stopLoading()
            webView.destroy()
        },
    )
}

private data class SlidesRequest(
    val markdown: String,
    val darkMode: Boolean,
    val editorSettings: EditorSettings,
    val initialSlide: Int,
)

private data class PreparedSlides(
    val request: SlidesRequest,
    val html: String,
    val maximumSlideIndex: Int,
    val preparationMillis: Long,
)

private data class WebPreviewDocument(
    val revision: Any,
    val html: String,
    val javaScriptEnabled: Boolean,
    val maximumSlideIndex: Int,
    val preparationMillis: Long,
)

private class PreviewWebViewSession(
    var router: PresentationAssetRouter,
    val controller: PreviewWebViewController,
    var onSlideChanged: (Int) -> Unit,
) {
    var revision: Any? = null
    var maximumSlideIndex = 0
    var active = false
    var expectedRelease = false
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val DiagnosticsTag = "MiaoYanPreview"
private const val PreviewPreparationDebounceMillis = 80L
private const val AutomaticRendererRestarts = 1
private const val BundledFontPath = "/presentation/jetbrains-mono.ttf"
