package com.tw93.miaoyan.android.ui

import com.tw93.miaoyan.android.data.LocalImagePolicy
import java.util.Base64

/** GitHub cmark-gfm backed fragment renderer and the preview content-policy boundary. */
object MarkdownRenderer {
    fun stripFrontmatter(markdown: String): String {
        if (!(markdown.startsWith("---\n") || markdown.startsWith("---\r\n"))) return markdown
        val lineEnding = if (markdown.startsWith("---\r\n")) "\r\n" else "\n"
        val close = markdown.indexOf("${lineEnding}---${lineEnding}", startIndex = 3 + lineEnding.length)
        if (close >= 0) return markdown.substring(close + (lineEnding.length * 2) + 3)
        val terminalClose = "${lineEnding}---"
        if (markdown.endsWith(terminalClose)) {
            val index = markdown.lastIndexOf(terminalClose)
            if (index >= 3 + lineEnding.length) return ""
        }
        return markdown
    }

    fun renderFragment(markdown: String): String = PreviewContentPolicy.rewrite(
        CmarkGfmNative.render(DeferredIframePolicy.preprocess(markdown)),
    )
}

internal object CmarkGfmNative {
    init {
        System.loadLibrary("miaoyan_cmark")
    }

    fun render(markdown: String): String = nativeRender(markdown.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)

    private external fun nativeRender(markdownUtf8: ByteArray): ByteArray
}

/** The single resource-policy boundary between trusted cmark output and WebView. */
internal object PreviewContentPolicy {
    fun rewrite(fragment: String): String {
        val imagesRewritten = Image.replace(fragment) { match ->
            val rawSource = decodeHtmlAttribute(match.groupValues[1])
            val escapedAlt = match.groupValues[2].ifBlank { "image" }
            when (val source = LocalImagePolicy.classifyMarkdownSource(rawSource)) {
                is LocalImagePolicy.MarkdownSource.Local ->
                    "<img src=\"${source.assetUrl}\" alt=\"$escapedAlt\" loading=\"lazy\" />"
                is LocalImagePolicy.MarkdownSource.External ->
                    "<a class=\"media-placeholder\" href=\"${escapeHtmlAttribute(source.url)}\" " +
                        "rel=\"noreferrer noopener\">[external image: $escapedAlt — tap to open]</a>"
                LocalImagePolicy.MarkdownSource.Unsupported ->
                    "<span class=\"media-placeholder\">[unavailable image: $escapedAlt]</span>"
            }
        }
        val embedsRewritten = DeferredEmbed.replace(imagesRewritten) { match ->
            val url = DeferredIframePolicy.urlForToken(match.groupValues[1])
                ?: return@replace "<span class=\"media-placeholder\">iframe blocked</span>"
            "<button type=\"button\" class=\"embed-placeholder\" data-embed=\"${escapeHtmlAttribute(url)}\">" +
                "Embedded content — tap to load</button>"
        }
        return ActiveMedia.replace(embedsRewritten) { match ->
            "<span class=\"media-placeholder\">${match.groupValues[1].lowercase()} blocked</span>"
        }
    }

    private fun decodeHtmlAttribute(value: String): String = HtmlEntity.replace(value) { match ->
        when (val entity = match.groupValues[1]) {
            "amp" -> "&"
            "quot" -> "\""
            "apos" -> "'"
            "lt" -> "<"
            "gt" -> ">"
            else -> {
                val codePoint = if (entity.startsWith("#x", ignoreCase = true)) {
                    entity.drop(2).toIntOrNull(16)
                } else {
                    entity.drop(1).toIntOrNull()
                }
                codePoint?.takeIf(Character::isValidCodePoint)?.let(Character::toChars)?.concatToString()
                    ?: match.value
            }
        }
    }

    private fun escapeHtmlAttribute(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

    private val Image = Regex(
        """<img src="([^"]*)" alt="([^"]*)"(?: title="[^"]*")? />""",
        RegexOption.IGNORE_CASE,
    )
    private val ActiveMedia = Regex(
        """<\s*(iframe|video|audio)\b[^>]*(?:>.*?<\s*/\s*\1\s*>|/\s*>)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val DeferredEmbed = Regex(
        """<a href="https://appassets\.androidplatform\.net/embed/([A-Za-z0-9_-]{1,4096})">[^<]*</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val HtmlEntity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt);")
}

/** Replaces only standalone, empty HTTPS iframe tags outside fenced code with an inert cmark link token. */
internal object DeferredIframePolicy {
    fun preprocess(markdown: String): String {
        var fence: Fence? = null
        return markdown.split('\n').joinToString("\n") { rawLine ->
            val hasCarriageReturn = rawLine.endsWith('\r')
            val line = if (hasCarriageReturn) rawLine.dropLast(1) else rawLine
            val fenceMarker = FenceMarker.find(line)
            if (fenceMarker != null) {
                val marker = fenceMarker.groupValues[1]
                fence = if (fence == null) {
                    Fence(marker.first(), marker.length)
                } else if (fence?.character == marker.first() && marker.length >= requireNotNull(fence).length) {
                    null
                } else {
                    fence
                }
                return@joinToString line + if (hasCarriageReturn) "\r" else ""
            }
            val replacement = if (fence == null) standaloneReplacement(line) else null
            (replacement ?: line) + if (hasCarriageReturn) "\r" else ""
        }
    }

    fun urlForToken(token: String): String? {
        if (!Token.matches(token)) return null
        val decoded = runCatching {
            Base64.getUrlDecoder().decode(token).toString(Charsets.UTF_8)
        }.getOrNull() ?: return null
        val media = LocalImagePolicy.deferredExternalMedia(decoded, LocalImagePolicy.ExternalMediaKind.Iframe)
            ?: return null
        return media.url.takeIf { it.startsWith("https://", ignoreCase = true) }
    }

    fun isAllowedFrameUrl(rawUrl: String): Boolean =
        LocalImagePolicy.deferredExternalMedia(rawUrl, LocalImagePolicy.ExternalMediaKind.Iframe)
            ?.url?.startsWith("https://", ignoreCase = true) == true

    private fun standaloneReplacement(line: String): String? {
        val attributes = StandaloneIframe.matchEntire(line)?.groupValues?.get(1) ?: return null
        val sources = Source.findAll(attributes).toList()
        if (sources.size != 1) return "[iframe blocked]"
        val rawUrl = sources.single().groupValues.drop(1).firstOrNull(String::isNotEmpty)
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.replace("&#39;", "'")
            ?: return "[iframe blocked]"
        val media = LocalImagePolicy.deferredExternalMedia(rawUrl, LocalImagePolicy.ExternalMediaKind.Iframe)
            ?: return "[iframe blocked]"
        if (!media.url.startsWith("https://", ignoreCase = true)) return "[iframe blocked]"
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(media.url.toByteArray(Charsets.UTF_8))
        return "[Embedded content — tap to load](https://appassets.androidplatform.net/embed/$token)"
    }

    private data class Fence(val character: Char, val length: Int)

    private val FenceMarker = Regex("""^[ ]{0,3}(`{3,}|~{3,})(?:.*)?$""")
    private val StandaloneIframe = Regex(
        """[ \t]*<iframe\b([^>\r\n]{0,4096})(?:></iframe\s*>|/>)\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val Source = Regex(
        """(?:^|\s)src\s*=\s*(?:"([^"]{1,2048})"|'([^']{1,2048})')""",
        RegexOption.IGNORE_CASE,
    )
    private val Token = Regex("[A-Za-z0-9_-]{1,4096}")
}

internal object PreviewNavigationPolicy {
    fun opensExternally(rawUrl: String, userActivated: Boolean): Boolean =
        userActivated && LocalImagePolicy.isAllowedExternalNavigation(rawUrl)
}
