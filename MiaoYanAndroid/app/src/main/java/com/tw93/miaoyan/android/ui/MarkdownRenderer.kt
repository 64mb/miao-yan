package com.tw93.miaoyan.android.ui

import com.tw93.miaoyan.android.data.LocalImagePolicy

/** GitHub cmark-gfm backed renderer with a deliberately inert WebView document contract. */
object MarkdownRenderer {
    fun renderDocument(markdown: String, darkMode: Boolean): String =
        renderDocument(markdown, darkMode, CmarkGfmNative::render)

    internal fun renderDocument(
        markdown: String,
        darkMode: Boolean,
        fragmentRenderer: (String) -> String,
    ): String {
        val rendered = fragmentRenderer(stripFrontmatter(markdown))
        val body = PreviewContentPolicy.rewrite(rendered)
        val background = if (darkMode) "#171717" else "#fffdfa"
        val foreground = if (darkMode) "#e8e4de" else "#242220"
        val muted = if (darkMode) "#aaa39a" else "#746f68"
        val code = if (darkMode) "#242321" else "#f4efe8"
        return """
            <!doctype html>
            <html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src ${LocalImagePolicy.AssetOrigin}; media-src 'none'; frame-src 'none'; connect-src 'none'; base-uri 'none'; form-action 'none'">
            <style>
              :root { color-scheme: ${if (darkMode) "dark" else "light"}; }
              * { box-sizing: border-box; }
              html, body { margin: 0; max-width: 100%; overflow-x: hidden; background: $background; color: $foreground; }
              body { padding: 22px 20px 48px; font: 17px/1.68 system-ui, sans-serif; overflow-wrap: anywhere; }
              h1,h2,h3,h4,h5,h6 { line-height: 1.25; margin: 1.35em 0 .55em; }
              h1 { font-size: 2em; } h2 { font-size: 1.55em; }
              p { margin: .8em 0; } a { color: #d36b1f; }
              code { background: $code; border-radius: 5px; padding: .12em .35em; font-family: ui-monospace, monospace; }
              pre { max-width: 100%; overflow-x: auto; background: $code; border-radius: 10px; padding: 14px; }
              pre code { padding: 0; } blockquote { margin-left: 0; padding-left: 14px; border-left: 3px solid #e59a56; color: $muted; }
              img,video,audio,iframe,table { max-width: 100%; }
              img { height: auto; display: block; margin: 1em auto; }
              hr { border: 0; border-top: 1px solid $muted; opacity: .35; margin: 2em 0; }
              ul { padding-left: 1.4em; }
              table { border-collapse: collapse; table-layout: fixed; width: 100%; }
              th,td { border: 1px solid $muted; overflow-wrap: anywhere; padding: .35em .65em; }
              .media-placeholder { display: inline-block; max-width: 100%; color: $muted; font-style: italic; overflow-wrap: anywhere; }
            </style></head><body>$body</body></html>
        """.trimIndent()
    }

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
