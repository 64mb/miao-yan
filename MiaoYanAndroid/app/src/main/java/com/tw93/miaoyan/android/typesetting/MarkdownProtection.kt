package com.tw93.miaoyan.android.typesetting

internal enum class ProtectedMarkdownKind {
    FRONTMATTER,
    FENCED_CODE,
    INLINE_CODE,
    MATH,
    RAW_HTML,
    LINK_TARGET,
    WIKILINK,
    URL,
}

internal data class ProtectedMarkdownFragment(
    val kind: ProtectedMarkdownKind,
    val text: String,
)

internal class ProtectedMarkdown private constructor(
    val source: String,
    private val original: String,
    private val replacements: List<Replacement>,
) {
    fun restoreAndValidate(formattedSource: String): String {
        var normalized = normalizeLineEndings(formattedSource)
        if (!hasTrailingLineBreak(original)) {
            normalized = normalized.removeSingleTrailingLineBreak()
        }
        if (original.lineSequence().count() > 1 && !normalized.contains('\n') && !normalized.contains('\r')) {
            throw MarkdownFormattingException("Prettier returned an invalid single-line result")
        }

        var restored = normalized
        for (replacement in replacements) {
            val first = restored.indexOf(replacement.token)
            if (first < 0 || first != restored.lastIndexOf(replacement.token)) {
                throw MarkdownFormattingException("A protected Markdown fragment was changed")
            }
            restored = restored.replaceRange(first, first + replacement.token.length, replacement.fragment.text)
        }

        val expected = collectRanges(original).map { ProtectedMarkdownFragment(it.kind, original.substring(it.start, it.end)) }
        val actual = collectRanges(restored).map { ProtectedMarkdownFragment(it.kind, restored.substring(it.start, it.end)) }
        if (actual != expected) {
            throw MarkdownFormattingException("Prettier changed protected Markdown syntax")
        }
        return restored
    }

    private fun normalizeLineEndings(formatted: String): String {
        val usesOnlyCrLf = original.contains("\r\n") && !original.replace("\r\n", "").contains('\n')
        return if (usesOnlyCrLf) formatted.replace("\r\n", "\n").replace("\n", "\r\n") else formatted
    }

    private fun String.removeSingleTrailingLineBreak(): String = when {
        endsWith("\r\n") -> dropLast(2)
        endsWith('\n') || endsWith('\r') -> dropLast(1)
        else -> this
    }

    private data class Replacement(
        val token: String,
        val fragment: ProtectedMarkdownFragment,
    )

    companion object {
        fun protect(markdown: String): ProtectedMarkdown {
            val ranges = collectRanges(markdown)
            var prefix = "MiaoYanProtected"
            while (markdown.contains(prefix)) prefix += "X"

            val replacements = ranges.mapIndexed { index, range ->
                Replacement(
                    token = prefix + index.toString().padStart(5, '0'),
                    fragment = ProtectedMarkdownFragment(range.kind, markdown.substring(range.start, range.end)),
                )
            }
            var protected = markdown
            ranges.indices.reversed().forEach { index ->
                val range = ranges[index]
                protected = protected.replaceRange(range.start, range.end, replacements[index].token)
            }
            return ProtectedMarkdown(protected, markdown, replacements)
        }

        internal fun fragments(markdown: String): List<ProtectedMarkdownFragment> =
            collectRanges(markdown).map { ProtectedMarkdownFragment(it.kind, markdown.substring(it.start, it.end)) }

        private fun collectRanges(markdown: String): List<SourceRange> {
            val ranges = mutableListOf<SourceRange>()
            val lines = lineSpans(markdown)

            collectFrontmatter(markdown, lines, ranges)
            collectFencedCode(markdown, lines, ranges)
            collectRegex(
                markdown,
                ranges,
                Regex("<([A-Za-z][\\w:-]*)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE),
                ProtectedMarkdownKind.RAW_HTML,
            )
            collectReferenceDefinitions(markdown, lines, ranges)
            collectInlineCode(markdown, ranges)
            collectDelimited(markdown, ranges, "$$", "$$", ProtectedMarkdownKind.MATH, multiline = true)
            collectDelimited(markdown, ranges, "\\[", "\\]", ProtectedMarkdownKind.MATH, multiline = true)
            collectDelimited(markdown, ranges, "\\(", "\\)", ProtectedMarkdownKind.MATH, multiline = false)
            collectDelimited(markdown, ranges, "$", "$", ProtectedMarkdownKind.MATH, multiline = false)
            collectDelimited(markdown, ranges, "[[", "]]", ProtectedMarkdownKind.WIKILINK, multiline = false)
            collectLinkTargets(markdown, ranges)
            collectRegex(
                markdown,
                ranges,
                Regex("<!--[\\s\\S]*?-->|<\\?[\\s\\S]*?\\?>|<![A-Za-z][^>]*>|</?[A-Za-z][^<>]*?>"),
                ProtectedMarkdownKind.RAW_HTML,
            )
            collectRegex(markdown, ranges, Regex("https?://[^\\s<>()]+"), ProtectedMarkdownKind.URL)
            return ranges.sortedBy(SourceRange::start)
        }

        private fun collectFrontmatter(
            markdown: String,
            lines: List<LineSpan>,
            ranges: MutableList<SourceRange>,
        ) {
            if (lines.firstOrNull()?.text(markdown) != "---") return
            val closing = lines.drop(1).firstOrNull { it.text(markdown) == "---" || it.text(markdown) == "..." }
                ?: return
            addRange(ranges, 0, closing.contentEnd, ProtectedMarkdownKind.FRONTMATTER)
        }

        private fun collectFencedCode(
            markdown: String,
            lines: List<LineSpan>,
            ranges: MutableList<SourceRange>,
        ) {
            var lineIndex = 0
            while (lineIndex < lines.size) {
                val line = lines[lineIndex]
                if (isCovered(line.start, ranges)) {
                    lineIndex += 1
                    continue
                }
                val candidate = unquoted(line.text(markdown))
                val marker = candidate.firstOrNull()?.takeIf { it == '`' || it == '~' }
                val markerLength = marker?.let { candidate.takeWhile { character -> character == it }.length } ?: 0
                if (marker == null || markerLength < 3) {
                    lineIndex += 1
                    continue
                }

                var closingIndex = lineIndex + 1
                while (closingIndex < lines.size) {
                    val closing = unquoted(lines[closingIndex].text(markdown))
                    val runLength = closing.takeWhile { it == marker }.length
                    if (runLength >= markerLength && closing.drop(runLength).isBlank()) break
                    closingIndex += 1
                }
                val end = if (closingIndex < lines.size) lines[closingIndex].contentEnd else markdown.length
                addRange(ranges, line.start, end, ProtectedMarkdownKind.FENCED_CODE)
                lineIndex = closingIndex + 1
            }
        }

        private fun collectReferenceDefinitions(
            markdown: String,
            lines: List<LineSpan>,
            ranges: MutableList<SourceRange>,
        ) {
            for (line in lines) {
                if (isCovered(line.start, ranges)) continue
                val value = unquoted(line.text(markdown))
                val close = value.indexOf(']')
                if (value.startsWith('[') && close > 0 && value.getOrNull(close + 1) == ':') {
                    addRange(ranges, line.start, line.contentEnd, ProtectedMarkdownKind.LINK_TARGET)
                }
            }
        }

        private fun collectInlineCode(markdown: String, ranges: MutableList<SourceRange>) {
            var index = 0
            while (index < markdown.length) {
                if (markdown[index] != '`' || isCovered(index, ranges) || isEscaped(markdown, index)) {
                    index += 1
                    continue
                }
                val runLength = markdown.drop(index).takeWhile { it == '`' }.length
                val delimiter = "`".repeat(runLength)
                var closing = markdown.indexOf(delimiter, index + runLength)
                while (closing >= 0 && isCovered(closing, ranges)) {
                    closing = markdown.indexOf(delimiter, closing + runLength)
                }
                if (closing >= 0) {
                    addRange(ranges, index, closing + runLength, ProtectedMarkdownKind.INLINE_CODE)
                    index = closing + runLength
                } else {
                    index += runLength
                }
            }
        }

        private fun collectDelimited(
            markdown: String,
            ranges: MutableList<SourceRange>,
            opening: String,
            closing: String,
            kind: ProtectedMarkdownKind,
            multiline: Boolean,
        ) {
            var index = 0
            while (index <= markdown.length - opening.length) {
                if (!markdown.startsWith(opening, index) || isCovered(index, ranges) || isEscaped(markdown, index)) {
                    index += 1
                    continue
                }
                if (opening == "$" && (markdown.startsWith("$$", index) || markdown.getOrNull(index - 1) == '$')) {
                    index += 1
                    continue
                }
                var end = markdown.indexOf(closing, index + opening.length)
                while (end >= 0) {
                    val crossesLine = !multiline && markdown.substring(index + opening.length, end).containsAnyLineBreak()
                    val invalidSingleDollar = opening == "$" && markdown.getOrNull(end + 1) == '$'
                    if (!isCovered(end, ranges) && !isEscaped(markdown, end) && !crossesLine && !invalidSingleDollar) break
                    end = markdown.indexOf(closing, end + closing.length)
                }
                if (end >= 0) {
                    addRange(ranges, index, end + closing.length, kind)
                    index = end + closing.length
                } else {
                    index += opening.length
                }
            }
        }

        private fun collectLinkTargets(markdown: String, ranges: MutableList<SourceRange>) {
            var index = markdown.indexOf("](")
            while (index >= 0) {
                val opening = index + 1
                if (!isCovered(opening, ranges)) {
                    var cursor = opening + 1
                    var depth = 1
                    while (cursor < markdown.length && depth > 0 && !markdown[cursor].isLineBreak()) {
                        if (!isEscaped(markdown, cursor)) {
                            when (markdown[cursor]) {
                                '(' -> depth += 1
                                ')' -> depth -= 1
                            }
                        }
                        cursor += 1
                    }
                    if (depth == 0) addRange(ranges, opening, cursor, ProtectedMarkdownKind.LINK_TARGET)
                }
                index = markdown.indexOf("](", index + 2)
            }
        }

        private fun collectRegex(
            markdown: String,
            ranges: MutableList<SourceRange>,
            regex: Regex,
            kind: ProtectedMarkdownKind,
        ) {
            regex.findAll(markdown).forEach { match ->
                addRange(ranges, match.range.first, match.range.last + 1, kind)
            }
        }

        private fun addRange(
            ranges: MutableList<SourceRange>,
            start: Int,
            end: Int,
            kind: ProtectedMarkdownKind,
        ) {
            if (start >= end || ranges.any { start < it.end && end > it.start }) return
            ranges += SourceRange(start, end, kind)
        }

        private fun isCovered(index: Int, ranges: List<SourceRange>): Boolean =
            ranges.any { index >= it.start && index < it.end }

        private fun isEscaped(markdown: String, index: Int): Boolean {
            var backslashes = 0
            var cursor = index - 1
            while (cursor >= 0 && markdown[cursor] == '\\') {
                backslashes += 1
                cursor -= 1
            }
            return backslashes % 2 == 1
        }

        private fun unquoted(line: String): String {
            var value = line.trimStart()
            while (value.startsWith('>')) value = value.drop(1).trimStart()
            return value
        }

        private fun lineSpans(markdown: String): List<LineSpan> {
            if (markdown.isEmpty()) return listOf(LineSpan(0, 0, 0))
            val lines = mutableListOf<LineSpan>()
            var start = 0
            while (start < markdown.length) {
                val newline = markdown.indexOf('\n', start)
                val fullEnd = if (newline < 0) markdown.length else newline + 1
                var contentEnd = if (newline < 0) markdown.length else newline
                if (contentEnd > start && markdown[contentEnd - 1] == '\r') contentEnd -= 1
                lines += LineSpan(start, contentEnd, fullEnd)
                start = fullEnd
            }
            return lines
        }

        private fun hasTrailingLineBreak(value: String): Boolean =
            value.endsWith('\n') || value.endsWith('\r')

        private fun String.containsAnyLineBreak(): Boolean = contains('\n') || contains('\r')

        private fun Char.isLineBreak(): Boolean = this == '\n' || this == '\r'

        private data class SourceRange(
            val start: Int,
            val end: Int,
            val kind: ProtectedMarkdownKind,
        )

        private data class LineSpan(
            val start: Int,
            val contentEnd: Int,
            val fullEnd: Int,
        ) {
            fun text(markdown: String): String = markdown.substring(start, contentEnd)
        }
    }
}
