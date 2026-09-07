package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.ui.MarkdownRenderer
import com.tw93.miaoyan.android.ui.PreviewContentPolicy
import com.tw93.miaoyan.android.ui.PreviewNavigationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun stripsLfAndCrlfFrontmatter() {
        assertEquals("# Note", MarkdownRenderer.stripFrontmatter("---\ntitle: Test\n---\n# Note"))
        assertEquals("# Note", MarkdownRenderer.stripFrontmatter("---\r\ntitle: Test\r\n---\r\n# Note"))
    }

    @Test
    fun leavesUnclosedFrontmatterUntouched() {
        val source = "---\ntitle: Test\n# Note"
        assertEquals(source, MarkdownRenderer.stripFrontmatter(source))
    }

    @Test
    fun documentStripsFrontmatterBeforeCallingNativeRenderer() {
        var nativeInput = ""
        val html = MarkdownRenderer.renderDocument("---\ntitle: Test\n---\n# Note", darkMode = false) { markdown ->
            nativeInput = markdown
            "<h1>Note</h1>"
        }

        assertEquals("# Note", nativeInput)
        assertTrue(html.contains("<h1>Note</h1>"))
    }

    @Test
    fun documentKeepsWebViewResourceContractClosed() {
        val html = MarkdownRenderer.renderDocument("text", darkMode = true) { "<p>text</p>" }

        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("img-src data:"))
        assertTrue(html.contains("media-src 'none'"))
        assertTrue(html.contains("frame-src 'none'"))
        assertTrue(html.contains("img,video,audio,iframe,table { max-width: 100%; }"))
    }

    @Test
    fun rewritesRemoteImagesWithoutCreatingANavigationDecision() {
        val fragment = "<p><img src=\"https://example.com/image.png\" alt=\"diagram\" /></p>"
        val rewritten = PreviewContentPolicy.rewrite(fragment)

        assertTrue(rewritten.contains("Remote image: diagram"))
        assertFalse(rewritten.contains("example.com"))
        assertFalse(rewritten.contains("<a "))
    }

    @Test
    fun rewritesActiveMediaAtThePolicyBoundary() {
        val rewritten = PreviewContentPolicy.rewrite(
            "<iframe src=\"https://example.com/embed\"></iframe><video src=\"clip.mp4\"></video>",
        )

        assertEquals(
            "<span class=\"media-placeholder\">iframe blocked</span>" +
                "<span class=\"media-placeholder\">video blocked</span>",
            rewritten,
        )
    }

    @Test
    fun onlyExplicitNavigationSchemesCanLeaveTheWebView() {
        assertTrue(PreviewNavigationPolicy.opensExternally("HTTPS"))
        assertTrue(PreviewNavigationPolicy.opensExternally("mailto"))
        assertFalse(PreviewNavigationPolicy.opensExternally("javascript"))
        assertFalse(PreviewNavigationPolicy.opensExternally("file"))
        assertFalse(PreviewNavigationPolicy.opensExternally(null))
    }
}
