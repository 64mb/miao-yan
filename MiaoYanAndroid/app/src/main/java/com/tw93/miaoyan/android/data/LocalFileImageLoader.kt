package com.tw93.miaoyan.android.data

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/** Streams a note-local image without exposing its app-private filesystem path to WebView. */
class LocalFileImageLoader(private val scope: LocalImagePolicy.NoteAssetScope) {
    fun load(rawUrl: String): WebResourceResponse {
        val fileName = LocalImagePolicy.fileNameForAssetUrl(rawUrl) ?: return errorResponse(403, "Forbidden")
        return runCatching {
            val image = LocalImagePolicy.resolveLocalImage(scope, fileName)
                ?: return errorResponse(404, "Not Found")
            val mimeType = LocalImagePolicy.mimeTypeFor(image.name, null)
                ?: return errorResponse(415, "Unsupported Media Type")
            WebResourceResponse(
                mimeType,
                null,
                200,
                "OK",
                ResponseHeaders,
                FileInputStream(image),
            )
        }.getOrElse { errorResponse(404, "Not Found") }
    }

    private fun errorResponse(statusCode: Int, reason: String): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        statusCode,
        reason,
        ResponseHeaders,
        ByteArrayInputStream(ByteArray(0)),
    )

    private companion object {
        val ResponseHeaders = mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
        )
    }
}
