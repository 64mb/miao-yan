package com.tw93.miaoyan.android.ui.editor

/** Markdown token colours mirror the named macOS editor colour assets. */
data class MarkdownSyntaxPalette(
    val body: Int,
    val heading: Int,
    val link: Int,
    val list: Int,
    val markup: Int,
    val codeBlock: Int,
) {
    fun colorFor(kind: MarkdownTokenKind): Int = when (kind) {
        MarkdownTokenKind.HEADING -> heading
        MarkdownTokenKind.LINK -> link
        MarkdownTokenKind.LIST -> list
        MarkdownTokenKind.MARKUP -> markup
        MarkdownTokenKind.CODE_BLOCK -> codeBlock
    }
}

object MarkdownEditorPalettes {
    // Resources/Images.xcassets/{title,link,list,html}.colorset plus the
    // light/dark code text values in MarkdownRuleHighlighter.swift.
    val Light = MarkdownSyntaxPalette(
        body = 0xD8000000.toInt(),
        heading = 0xFF7A3DAD.toInt(),
        link = 0xFF05A699.toInt(),
        list = 0xFF826B29.toInt(),
        markup = 0xFFF28A21.toInt(),
        codeBlock = 0xFF333333.toInt(),
    )

    val Dark = MarkdownSyntaxPalette(
        body = 0xD8FFFFFF.toInt(),
        heading = 0xFFA178FF.toInt(),
        link = 0xFF61FFC9.toInt(),
        list = 0xFFC4C7C4.toInt(),
        markup = 0xFFFFC985.toInt(),
        codeBlock = 0xFFCCCCCC.toInt(),
    )

    fun forDarkMode(darkMode: Boolean): MarkdownSyntaxPalette = if (darkMode) Dark else Light
}

enum class MarkdownTokenKind {
    HEADING,
    LINK,
    LIST,
    MARKUP,
    CODE_BLOCK,
}

data class MarkdownSyntaxToken(
    val start: Int,
    val endExclusive: Int,
    val kind: MarkdownTokenKind,
)

/**
 * Lightweight Markdown tokenization for editor colouring.
 *
 * Markers remain visible and ranges never change the source text, so cursor
 * positions and composing text stay stable. Fenced code is emitted last and
 * therefore takes precedence over Markdown-looking content inside the fence.
 */
object MarkdownSyntaxTokenizer {
    private val heading = Regex("(?m)^#{1,6}[\\t ]+[^\\n]*$")
    private val setextHeading = Regex("(?m)^.+\\n(?:=+|-+)[\\t ]*$")
    private val blockQuote = Regex("(?m)^[\\t ]*>[^\\n]*$")
    private val listMarker = Regex("(?m)^[\\t ]*(?:[-*+]|\\d+[.)])[\\t ]+")
    private val taskMarker = Regex("(?m)^[\\t ]*[-*+][\\t ]+\\[[ xX]][\\t ]*")
    private val bold = Regex("(\\*\\*|__)(?=\\S).+?(?<=\\S)\\1")
    private val italic = Regex("(?<![*_\\w])([*_])(?=[^*_\\s])[^*_\\n]+?(?<=\\S)\\1(?![*_\\w])")
    private val strike = Regex("~~(?=\\S).+?(?<=\\S)~~")
    private val inlineCode = Regex("`[^`\\n]+`")
    private val inlineLink = Regex("!?\\[[^]\\n]+]\\([^\\n)]+\\)")
    private val referenceLink = Regex("!?\\[[^]\\n]+]\\[[^]\\n]*]")
    private val wikiLink = Regex("\\[\\[[^]\\n]+]]")
    private val html = Regex("</?[A-Za-z][^>\\n]*>")
    private val fencedCode = Regex("(?m)^```[^\\n]*(?:\\n[\\s\\S]*?\\n```[\\t ]*$|$)")
    private val frontmatter = Regex("\\A---(?:\\r\\n|\\n)[\\s\\S]*?(?:\\r\\n|\\n)---[\\t ]*(?:(?:\\r\\n|\\n)|\\z)")

    fun tokenize(markdown: String): List<MarkdownSyntaxToken> {
        val fences = fencedCode.findAll(markdown).map { it.range }.toList()
        val excluded = fences.toMutableList()
        frontmatter.find(markdown)?.range?.let(excluded::add)
        return buildList {
            addMatches(italic, markdown, MarkdownTokenKind.HEADING, excluded)
            addMatches(bold, markdown, MarkdownTokenKind.MARKUP, excluded)
            addMatches(strike, markdown, MarkdownTokenKind.MARKUP, excluded)
            addMatches(inlineCode, markdown, MarkdownTokenKind.MARKUP, excluded)
            addMatches(setextHeading, markdown, MarkdownTokenKind.HEADING, excluded)
            addMatches(heading, markdown, MarkdownTokenKind.HEADING, excluded)
            addMatches(listMarker, markdown, MarkdownTokenKind.LIST, excluded)
            addMatches(taskMarker, markdown, MarkdownTokenKind.LIST, excluded)
            addMatches(inlineLink, markdown, MarkdownTokenKind.LINK, excluded)
            addMatches(referenceLink, markdown, MarkdownTokenKind.LINK, excluded)
            addMatches(wikiLink, markdown, MarkdownTokenKind.LINK, excluded)
            addMatches(blockQuote, markdown, MarkdownTokenKind.LIST, excluded)
            addMatches(html, markdown, MarkdownTokenKind.MARKUP, excluded)
            fences.forEach { range ->
                add(MarkdownSyntaxToken(range.first, range.last + 1, MarkdownTokenKind.CODE_BLOCK))
            }
        }
    }

    private fun MutableList<MarkdownSyntaxToken>.addMatches(
        regex: Regex,
        markdown: String,
        kind: MarkdownTokenKind,
        excludedRanges: List<IntRange>,
    ) {
        regex.findAll(markdown).forEach { match ->
            if (excludedRanges.none { it.overlaps(match.range) }) {
                add(MarkdownSyntaxToken(match.range.first, match.range.last + 1, kind))
            }
        }
    }

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
}

data class MarkdownHighlightRange(val start: Int, val endExclusive: Int)

/** Keeps regex work bounded while preserving full-document fidelity for normal notes. */
object MarkdownHighlightPolicy {
    const val MAX_FULL_DOCUMENT_CHARACTERS = 120_000
    const val MAX_FULL_DOCUMENT_LINES = 4_000
    const val MAX_LOCAL_SCAN_CHARACTERS = 16_384

    fun shouldHighlightFullDocument(markdown: CharSequence): Boolean {
        if (markdown.length > MAX_FULL_DOCUMENT_CHARACTERS) return false
        var lines = 1
        markdown.forEach { character ->
            if (character == '\n' && ++lines > MAX_FULL_DOCUMENT_LINES) return false
        }
        return true
    }

    fun resolveRange(markdown: CharSequence, changedStart: Int, changedEndExclusive: Int): MarkdownHighlightRange {
        if (shouldHighlightFullDocument(markdown)) return MarkdownHighlightRange(0, markdown.length)
        if (markdown.isEmpty()) return MarkdownHighlightRange(0, 0)

        val safeStart = changedStart.coerceIn(0, markdown.length)
        val safeEnd = changedEndExclusive.coerceIn(safeStart, markdown.length)
        val localEnd = if (safeEnd - safeStart > MAX_LOCAL_SCAN_CHARACTERS) safeStart else safeEnd
        var start = safeStart
        while (start > 0 && markdown[start - 1] != '\n' && safeStart - start < MAX_LOCAL_SCAN_CHARACTERS / 2) {
            start--
        }
        var end = localEnd
        while (end < markdown.length && markdown[end] != '\n' && end - start < MAX_LOCAL_SCAN_CHARACTERS) {
            end++
        }
        if (end < markdown.length && markdown[end] == '\n' && end - start < MAX_LOCAL_SCAN_CHARACTERS) end++
        return MarkdownHighlightRange(start, end)
    }
}
