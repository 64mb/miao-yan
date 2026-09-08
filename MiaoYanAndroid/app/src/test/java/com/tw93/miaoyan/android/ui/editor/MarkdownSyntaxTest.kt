package com.tw93.miaoyan.android.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSyntaxTest {
    @Test
    fun `light palette matches macOS named editor colors`() {
        assertEquals(0xD8000000.toInt(), MarkdownEditorPalettes.Light.body)
        assertEquals(0xFF7A3DAD.toInt(), MarkdownEditorPalettes.Light.heading)
        assertEquals(0xFF05A699.toInt(), MarkdownEditorPalettes.Light.link)
        assertEquals(0xFF826B29.toInt(), MarkdownEditorPalettes.Light.list)
        assertEquals(0xFFF28A21.toInt(), MarkdownEditorPalettes.Light.markup)
        assertEquals(0xFF333333.toInt(), MarkdownEditorPalettes.Light.codeBlock)
        assertEquals(0xFF7A3DAD.toInt(), MarkdownEditorPalettes.Light.colorFor(MarkdownTokenKind.HEADING))
        assertEquals(0xFF05A699.toInt(), MarkdownEditorPalettes.Light.colorFor(MarkdownTokenKind.LINK))
        assertEquals(0xFF826B29.toInt(), MarkdownEditorPalettes.Light.colorFor(MarkdownTokenKind.LIST))
        assertEquals(0xFFF28A21.toInt(), MarkdownEditorPalettes.Light.colorFor(MarkdownTokenKind.MARKUP))
        assertEquals(0xFF333333.toInt(), MarkdownEditorPalettes.Light.colorFor(MarkdownTokenKind.CODE_BLOCK))
    }

    @Test
    fun `dark palette matches macOS named editor colors`() {
        assertEquals(0xD8FFFFFF.toInt(), MarkdownEditorPalettes.Dark.body)
        assertEquals(0xFFA178FF.toInt(), MarkdownEditorPalettes.Dark.heading)
        assertEquals(0xFF61FFC9.toInt(), MarkdownEditorPalettes.Dark.link)
        assertEquals(0xFFC4C7C4.toInt(), MarkdownEditorPalettes.Dark.list)
        assertEquals(0xFFFFC985.toInt(), MarkdownEditorPalettes.Dark.markup)
        assertEquals(0xFFCCCCCC.toInt(), MarkdownEditorPalettes.Dark.codeBlock)
        assertEquals(0xFFA178FF.toInt(), MarkdownEditorPalettes.Dark.colorFor(MarkdownTokenKind.HEADING))
        assertEquals(0xFF61FFC9.toInt(), MarkdownEditorPalettes.Dark.colorFor(MarkdownTokenKind.LINK))
        assertEquals(0xFFC4C7C4.toInt(), MarkdownEditorPalettes.Dark.colorFor(MarkdownTokenKind.LIST))
        assertEquals(0xFFFFC985.toInt(), MarkdownEditorPalettes.Dark.colorFor(MarkdownTokenKind.MARKUP))
        assertEquals(0xFFCCCCCC.toInt(), MarkdownEditorPalettes.Dark.colorFor(MarkdownTokenKind.CODE_BLOCK))
    }

    @Test
    fun `token categories follow macOS Markdown editor mapping`() {
        val markdown = """# Heading
            |*emphasis* and **strong** with `code`
            |> quote
            |- [x] task
            |[link](https://example.com) and [[Wiki]]
            |<mark>html</mark>
        """.trimMargin()

        val tokens = MarkdownSyntaxTokenizer.tokenize(markdown)

        assertToken(markdown, tokens, "# Heading", MarkdownTokenKind.HEADING)
        assertToken(markdown, tokens, "*emphasis*", MarkdownTokenKind.HEADING)
        assertToken(markdown, tokens, "**strong**", MarkdownTokenKind.MARKUP)
        assertToken(markdown, tokens, "`code`", MarkdownTokenKind.MARKUP)
        assertToken(markdown, tokens, "> quote", MarkdownTokenKind.LIST)
        assertToken(markdown, tokens, "- [x] ", MarkdownTokenKind.LIST)
        assertToken(markdown, tokens, "[link](https://example.com)", MarkdownTokenKind.LINK)
        assertToken(markdown, tokens, "[[Wiki]]", MarkdownTokenKind.LINK)
        assertToken(markdown, tokens, "<mark>", MarkdownTokenKind.MARKUP)
    }

    @Test
    fun `fenced code owns its range without nested Markdown tokens`() {
        val markdown = "```md\n# not a heading\n[not a link](target)\n```"
        val tokens = MarkdownSyntaxTokenizer.tokenize(markdown)
        val fence = tokens.last()

        assertEquals(MarkdownTokenKind.CODE_BLOCK, fence.kind)
        assertEquals(0, fence.start)
        assertEquals(markdown.length, fence.endExclusive)
        assertTrue(tokens.none { it.kind == MarkdownTokenKind.HEADING })
        assertTrue(tokens.none { it.kind == MarkdownTokenKind.LINK })
    }

    @Test
    fun `frontmatter stays unstyled while image and links use link palette`() {
        val markdown = """---
            |date: 2026-09-07
            |image: /i/cover.png
            |---
            |![cover](/i/cover.png)
            |[document](/files/spec.pdf)
            |[[Related note]]
        """.trimMargin()

        val tokens = MarkdownSyntaxTokenizer.tokenize(markdown)
        val frontmatterEnd = markdown.indexOf("---", startIndex = 3) + 3

        assertTrue(tokens.none { it.start < frontmatterEnd })
        assertToken(markdown, tokens, "![cover](/i/cover.png)", MarkdownTokenKind.LINK)
        assertToken(markdown, tokens, "[document](/files/spec.pdf)", MarkdownTokenKind.LINK)
        assertToken(markdown, tokens, "[[Related note]]", MarkdownTokenKind.LINK)
    }

    @Test
    fun `large notes use a bounded paragraph local range`() {
        val line = "plain text that is intentionally not Markdown\n"
        val markdown = line.repeat(MarkdownHighlightPolicy.MAX_FULL_DOCUMENT_LINES + 1)
        val changedStart = markdown.length / 2

        val range = MarkdownHighlightPolicy.resolveRange(markdown, changedStart, changedStart + 1)

        assertTrue(range.start <= changedStart)
        assertTrue(range.endExclusive > changedStart)
        assertTrue(range.endExclusive - range.start <= MarkdownHighlightPolicy.MAX_LOCAL_SCAN_CHARACTERS)
    }

    @Test
    fun `normal notes retain full document highlighting`() {
        val markdown = "# Heading\n\nBody\n"

        assertTrue(MarkdownHighlightPolicy.shouldHighlightFullDocument(markdown))
        assertEquals(MarkdownHighlightRange(0, markdown.length), MarkdownHighlightPolicy.resolveRange(markdown, 3, 4))
    }

    private fun assertToken(
        markdown: String,
        tokens: List<MarkdownSyntaxToken>,
        expectedText: String,
        kind: MarkdownTokenKind,
    ) {
        assertTrue(
            tokens.any { token ->
                token.kind == kind && markdown.substring(token.start, token.endExclusive) == expectedText
            },
        )
    }
}
