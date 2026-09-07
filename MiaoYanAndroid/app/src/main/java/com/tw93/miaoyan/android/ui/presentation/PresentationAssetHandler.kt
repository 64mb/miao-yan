package com.tw93.miaoyan.android.ui.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import com.tw93.miaoyan.android.R
import com.tw93.miaoyan.android.data.LocalFileImageLoader
import com.tw93.miaoyan.android.data.LocalFileAttachmentOpener
import com.tw93.miaoyan.android.data.LocalImagePolicy
import java.io.ByteArrayInputStream

/** Pluggable boundary for note-local images; WebView never sees a filesystem path. */
fun interface PresentationImageHandler {
    fun open(assetUrl: String): WebResourceResponse?

    fun openAttachment(context: Context, assetUrl: String): Boolean = false

    companion object {
        val DenyAll = PresentationImageHandler { null }
    }
}

class AppPrivatePresentationImageHandler(scope: LocalImagePolicy.NoteAssetScope) : PresentationImageHandler {
    private val loader = LocalFileImageLoader(scope)
    private val attachmentOpener = LocalFileAttachmentOpener(scope)

    override fun open(assetUrl: String): WebResourceResponse = loader.load(assetUrl)

    override fun openAttachment(context: Context, assetUrl: String): Boolean = attachmentOpener.open(context, assetUrl)
}

internal class PresentationAssetRouter(
    private val context: Context,
    private val imageHandler: PresentationImageHandler,
    private val onResourceOpened: (String) -> Unit = {},
) {
    fun open(uri: Uri): WebResourceResponse {
        val rawUrl = uri.toString()
        if (uri.scheme != "https" || uri.encodedAuthority != AssetHost || uri.query != null || uri.fragment != null) {
            return blockedResponse()
        }
        onResourceOpened(uri.encodedPath.orEmpty())
        return when (uri.encodedPath) {
            "/presentation/reveal.js" -> bundled("presentation/reveal.js", "text/javascript")
            "/presentation/reveal.css" -> bundled("presentation/reveal.css", "text/css")
            "/presentation/jetbrains-mono.ttf" -> bundledFont()
            "/favicon.ico" -> blockedResponse(204, "No Content")
            else -> if (LocalImagePolicy.fileNameForAssetUrl(rawUrl) != null) {
                imageHandler.open(rawUrl) ?: blockedResponse()
            } else {
                blockedResponse()
            }
        }
    }

    fun openAttachment(context: Context, rawUrl: String): Boolean = imageHandler.openAttachment(context, rawUrl)

    private fun bundled(path: String, mimeType: String): WebResourceResponse = runCatching {
        response(mimeType, context.assets.open(path))
    }.getOrElse { blockedResponse(404, "Not Found") }

    @SuppressLint("ResourceType")
    private fun bundledFont(): WebResourceResponse = runCatching {
        response("font/ttf", context.resources.openRawResource(R.font.jetbrains_mono_regular))
    }.getOrElse { blockedResponse(404, "Not Found") }
}

internal fun blockedResponse(statusCode: Int = 403, reason: String = "Forbidden"): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", statusCode, reason, ResponseHeaders, ByteArrayInputStream(ByteArray(0)))

private fun response(mimeType: String, stream: java.io.InputStream): WebResourceResponse =
    WebResourceResponse(mimeType, null, 200, "OK", ResponseHeaders, stream)

private const val AssetHost = "appassets.androidplatform.net"
private val ResponseHeaders = mapOf(
    "Cache-Control" to "no-store",
    "X-Content-Type-Options" to "nosniff",
)
