package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.ui.MarkdownRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CmarkGfmRendererInstrumentedTest {
    @Test
    fun rendersGitHubFlavoredMarkdownExtensionsThroughJni() {
        val markdown = """
            | ~~old~~ | new |
            | --- | --- |
            | no | yes |

            - [x] shipped

            visit www.example.com
        """.trimIndent()
        val html = MarkdownRenderer.renderFragment(markdown)

        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<del>old</del>"))
        assertTrue(html.contains("type=\"checkbox\" checked=\"\" disabled=\"\""))
        assertTrue(html.contains("href=\"http://www.example.com\""))
    }

    @Test
    fun keepsRawHtmlAndDangerousLinksInCmarkSafeMode() {
        val html = MarkdownRenderer.renderFragment(
            "<iframe src=\"https://example.com\"></iframe>\n\n[bad](javascript:alert(1))",
        )

        assertTrue(html.contains("raw HTML omitted"))
        assertFalse(html.contains("<iframe"))
        assertFalse(html.contains("javascript:"))
    }

    @Test
    fun rewritesRemoteMarkdownImagesBeforeWebView() {
        val html = MarkdownRenderer.renderFragment("![diagram](https://example.com/image.png)")

        assertTrue(html.contains("[external image: diagram — tap to open]"))
        assertTrue(html.contains("href=\"https://example.com/image.png\""))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun preservesUtf8AcrossTheJniBoundary() {
        val html = MarkdownRenderer.renderFragment("Привет, 世界 👋")

        assertTrue(html.contains("Привет, 世界 👋"))
    }
}
