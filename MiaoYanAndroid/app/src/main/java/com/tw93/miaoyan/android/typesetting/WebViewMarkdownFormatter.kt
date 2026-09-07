package com.tw93.miaoyan.android.typesetting

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Runs pinned Prettier browser bundles in a private, data-only WebView.
 *
 * The WebView has no file/content access, storage, windows, or network loads.
 * Kotlin starts a Promise and polls a local in-page result map, so no JavaScript
 * interface is exposed to page code.
 */
class WebViewMarkdownFormatter(context: Context) : MarkdownFormatter {
    private val appContext = context.applicationContext
    private val pageLoader = BundledPrettierPageLoader(appContext)
    private val mutex = Mutex()
    private var webView: WebView? = null

    override suspend fun format(markdown: String): String {
        if (markdown.toByteArray().size > MAX_INPUT_BYTES) {
            throw MarkdownFormattingException("Markdown is too large to format safely")
        }
        try {
            return withTimeout(FORMAT_TIMEOUT_MILLIS) {
                val protected = withContext(Dispatchers.Default) { ProtectedMarkdown.protect(markdown) }
                val formatterPage = pageLoader.load()
                mutex.lock()
                try {
                    val formatted = withContext(Dispatchers.Main.immediate) {
                        val formatterView = webView ?: createWebView(formatterPage).also { webView = it }
                        formatInPage(formatterView, protected.source)
                    }
                    withContext(Dispatchers.Default) { protected.restoreAndValidate(formatted) }
                } finally {
                    mutex.unlock()
                }
            }
        } catch (error: MarkdownFormattingException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw MarkdownFormattingException("Local Markdown formatting failed", error)
        }
    }

    override fun close() {
        val destroy = Runnable {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) destroy.run() else Handler(Looper.getMainLooper()).post(destroy)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private suspend fun createWebView(page: String): WebView {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val view = WebView(appContext)
            view.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true
                domStorageEnabled = false
                databaseEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                override fun onPageFinished(view: WebView, url: String) {
                    if (continuation.isActive) continuation.resume(view)
                }
            }
            continuation.invokeOnCancellation { view.destroy() }
            view.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
        }
    }

    private suspend fun formatInPage(view: WebView, markdown: String): String {
        val requestId = UUID.randomUUID().toString()
        val started = view.evaluateJavascript(
            "window.miaoyanStart(${JSONObject.quote(requestId)},${JSONObject.quote(markdown)})",
        )
        if (started != "true") throw MarkdownFormattingException("Prettier did not accept the format request")

        while (true) {
            val rawResult = view.evaluateJavascript("window.miaoyanTake(${JSONObject.quote(requestId)})")
            if (rawResult != "null") {
                val result = JSONObject(rawResult)
                if (!result.optBoolean("ok")) {
                    throw MarkdownFormattingException(result.optString("error", "Prettier rejected the Markdown"))
                }
                return result.getString("value")
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private suspend fun WebView.evaluateJavascript(script: String): String =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result ?: "null")
            }
        }

    private companion object {
        const val MAX_INPUT_BYTES = 4 * 1024 * 1024
        // Includes a cold system-WebView process start, which can exceed 20s
        // on a software-rendered emulator even though formatting is local.
        const val FORMAT_TIMEOUT_MILLIS = 60_000L
        const val POLL_INTERVAL_MILLIS = 16L
    }
}

/** Lazy so opening the editor never reads or decodes the bundled JavaScript. */
internal class BundledPrettierPageLoader(
    private val readAsset: (String) -> String,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(context: Context) : this(
        readAsset = context.applicationContext.assets.let { assets ->
            { path -> assets.open(path).bufferedReader().use { it.readText() } }
        },
        ioDispatcher = Dispatchers.IO,
    )

    private val mutex = Mutex()

    @Volatile
    private var cachedPage: String? = null

    suspend fun load(): String {
        cachedPage?.let { return it }
        mutex.lock()
        try {
            cachedPage?.let { return it }
            return withContext(ioDispatcher) {
                buildFormatterPage(
                    prettier = readAsset(STANDALONE_ASSET),
                    markdownPlugin = readAsset(MARKDOWN_PLUGIN_ASSET),
                )
            }.also { cachedPage = it }
        } finally {
            mutex.unlock()
        }
    }

    private fun buildFormatterPage(prettier: String, markdownPlugin: String): String = """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'">
        </head>
        <body>
        <script>$prettier</script>
        <script>$markdownPlugin</script>
        <script>
        (() => {
          "use strict";
          const results = Object.create(null);
          window.miaoyanStart = (id, source) => {
            if (Object.prototype.hasOwnProperty.call(results, id)) return false;
            results[id] = null;
            Promise.resolve(prettier.format(source, {
              parser: "markdown",
              plugins: [prettierPlugins.markdown],
              proseWrap: "preserve",
              htmlWhitespaceSensitivity: "ignore",
              embeddedLanguageFormatting: "off"
            })).then(
              value => { results[id] = { ok: true, value }; },
              error => { results[id] = { ok: false, error: String(error).slice(0, 500) }; }
            );
            return true;
          };
          window.miaoyanTake = id => {
            const result = results[id];
            if (result == null) return null;
            delete results[id];
            return result;
          };
        })();
        </script>
        </body>
        </html>
    """.trimIndent()

    private companion object {
        const val STANDALONE_ASSET = "prettier/standalone.js"
        const val MARKDOWN_PLUGIN_ASSET = "prettier/markdown.js"
    }
}
