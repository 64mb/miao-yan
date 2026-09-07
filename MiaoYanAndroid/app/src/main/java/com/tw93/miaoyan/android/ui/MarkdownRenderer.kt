package com.tw93.miaoyan.android.ui

import com.tw93.miaoyan.android.data.LocalImagePolicy

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

    fun renderFragment(markdown: String): String = PreviewContentPolicy.rewrite(CmarkGfmNative.render(markdown))
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
        return ActiveMedia.replace(imagesRewritten) { match ->
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
    private val HtmlEntity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt);")
}

internal object PreviewNavigationPolicy {
    fun opensExternally(rawUrl: String, userActivated: Boolean): Boolean =
        userActivated && LocalImagePolicy.isAllowedExternalNavigation(rawUrl)
}
