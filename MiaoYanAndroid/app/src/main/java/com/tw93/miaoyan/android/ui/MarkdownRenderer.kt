package com.tw93.miaoyan.android.ui

import java.net.URI

/**
 * Deliberately bounded renderer for the first executable prototype.
 *
 * It escapes raw HTML and runs with JavaScript disabled. The production renderer
 * will replace this class with the cmark-gfm JNI adapter described in the plan.
 */
object MarkdownRenderer {
    fun renderDocument(markdown: String, darkMode: Boolean): String {
        val body = renderFragment(stripFrontmatter(markdown))
        val background = if (darkMode) "#171717" else "#fffdfa"
        val foreground = if (darkMode) "#e8e4de" else "#242220"
        val muted = if (darkMode) "#aaa39a" else "#746f68"
        val code = if (darkMode) "#242321" else "#f4efe8"
        return """
            <!doctype html>
            <html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:">
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
              img,video,iframe,table { max-width: 100%; }
              hr { border: 0; border-top: 1px solid $muted; opacity: .35; margin: 2em 0; }
              ul { padding-left: 1.4em; } .asset { color: $muted; font-style: italic; }
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

    fun renderFragment(markdown: String): String {
        val output = StringBuilder()
        val paragraph = mutableListOf<String>()
        var inCode = false
        var fence = ""
        var listOpen = false

        fun closeParagraph() {
            if (paragraph.isNotEmpty()) {
                output.append("<p>").append(inline(paragraph.joinToString(" "))).append("</p>")
                paragraph.clear()
            }
        }

        fun closeList() {
            if (listOpen) {
                output.append("</ul>")
                listOpen = false
            }
        }

        markdown.lineSequence().forEach { line ->
            val trimmed = line.trimEnd()
            if (inCode) {
                if (trimmed.trimStart().startsWith(fence)) {
                    output.append("</code></pre>")
                    inCode = false
                } else {
                    output.append(escape(trimmed)).append('\n')
                }
                return@forEach
            }

            val leftTrimmed = trimmed.trimStart()
            if (leftTrimmed.startsWith("```") || leftTrimmed.startsWith("~~~")) {
                closeParagraph()
                closeList()
                fence = leftTrimmed.take(3)
                val language = leftTrimmed.drop(3).trim().take(32).filter { it.isLetterOrDigit() || it in "_+-" }
                output.append("<pre><code")
                if (language.isNotEmpty()) output.append(" class=\"language-").append(language).append("\"")
                output.append(">")
                inCode = true
                return@forEach
            }

            if (trimmed.isBlank()) {
                closeParagraph()
                closeList()
                return@forEach
            }

            val heading = Heading.matchEntire(trimmed)
            if (heading != null) {
                closeParagraph()
                closeList()
                val level = heading.groupValues[1].length.coerceIn(1, 6)
                output.append("<h$level>").append(inline(heading.groupValues[2])).append("</h$level>")
                return@forEach
            }

            val listItem = ListItem.matchEntire(trimmed)
            if (listItem != null) {
                closeParagraph()
                if (!listOpen) {
                    output.append("<ul>")
                    listOpen = true
                }
                var item = listItem.groupValues[1]
                val task = Task.matchEntire(item)
                output.append("<li>")
                if (task != null) {
                    output.append("<input type=\"checkbox\" disabled")
                    if (task.groupValues[1].equals("x", ignoreCase = true)) output.append(" checked")
                    output.append("> ")
                    item = task.groupValues[2]
                }
                output.append(inline(item)).append("</li>")
                return@forEach
            }

            closeList()
            if (trimmed.startsWith("> ")) {
                closeParagraph()
                output.append("<blockquote>").append(inline(trimmed.drop(2))).append("</blockquote>")
            } else if (HorizontalRule.matches(trimmed)) {
                closeParagraph()
                output.append("<hr>")
            } else {
                paragraph += trimmed
            }
        }

        closeParagraph()
        closeList()
        if (inCode) output.append("</code></pre>")
        return output.toString()
    }

    private fun inline(value: String): String {
        val codeValues = mutableListOf<String>()
        var rendered = InlineCode.replace(value) { match ->
            val token = "\u0000${codeValues.size}\u0000"
            codeValues += "<code>${escape(match.groupValues[1])}</code>"
            token
        }
        rendered = escape(rendered)
        rendered = Image.replace(rendered) { match ->
            "<span class=\"asset\">[image: ${match.groupValues[1]}]</span>"
        }
        rendered = Link.replace(rendered) { match ->
            val href = safeLink(match.groupValues[2])
            if (href == null) match.value else "<a href=\"${escape(href)}\">${match.groupValues[1]}</a>"
        }
        rendered = Strong.replace(rendered, "<strong>$1</strong>")
        rendered = Strike.replace(rendered, "<del>$1</del>")
        rendered = Emphasis.replace(rendered, "<em>$1</em>")
        codeValues.forEachIndexed { index, code -> rendered = rendered.replace("\u0000$index\u0000", code) }
        return rendered
    }

    private fun safeLink(raw: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme?.lowercase() in setOf("https", "http", "mailto")) raw else null
    }.getOrNull()

    private fun escape(value: String): String = buildString(value.length) {
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

    private val Heading = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
    private val ListItem = Regex("^\\s*[-*+]\\s+(.+)$")
    private val Task = Regex("^\\[([ xX])]\\s+(.+)$")
    private val HorizontalRule = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val InlineCode = Regex("`([^`]+)`")
    private val Image = Regex("!\\[([^]]*)]\\(([^)]+)\\)")
    private val Link = Regex("\\[([^]]+)]\\(([^)]+)\\)")
    private val Strong = Regex("\\*\\*(.+?)\\*\\*")
    private val Strike = Regex("~~(.+?)~~")
    private val Emphasis = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
}
