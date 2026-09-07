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
        assertTrue(html.contains("img-src https://appassets.androidplatform.net"))
        assertTrue(html.contains("media-src 'none'"))
        assertTrue(html.contains("frame-src 'none'"))
        assertTrue(html.contains("connect-src 'none'"))
        assertTrue(html.contains("img,video,audio,iframe,table { max-width: 100%; }"))
    }

    @Test
    fun rewritesCmarkLocalImageToSyntheticOrigin() {
        val fragment = "<p><img src=\"/i/my%20photo.png\" alt=\"diagram\" /></p>"
        val rewritten = PreviewContentPolicy.rewrite(fragment)

        assertTrue(
            rewritten.contains(
                "<img src=\"https://appassets.androidplatform.net/i/my%20photo.png\"",
            ),
        )
        assertTrue(rewritten.contains("loading=\"lazy\""))
    }

    @Test
    fun decodesCmarkHtmlAttributeBeforeApplyingLocalPolicy() {
        val fragment = "<img src=\"/i/a&amp;b.png\" alt=\"A &amp; B\" />"
        val rewritten = PreviewContentPolicy.rewrite(fragment)

        assertTrue(rewritten.contains("/i/a%26b.png"))
        assertTrue(rewritten.contains("alt=\"A &amp; B\""))
    }

    @Test
    fun rewritesRemoteImageAsExplicitExternalLinkWithoutLoadingIt() {
        val fragment = "<p><img src=\"https://example.com/image.png?a=1&amp;b=2\" alt=\"diagram\" /></p>"
        val rewritten = PreviewContentPolicy.rewrite(fragment)

        assertTrue(rewritten.contains("[external image: diagram — tap to open]"))
        assertTrue(rewritten.contains("href=\"https://example.com/image.png?a=1&amp;b=2\""))
        assertFalse(rewritten.contains("<img src=\"https://example.com"))
    }

    @Test
    fun rejectsTraversalFromCmarkOutput() {
        val rewritten = PreviewContentPolicy.rewrite(
            "<img src=\"/i/%2e%2e%2fsecret.png\" alt=\"secret\" />",
        )

        assertTrue(rewritten.contains("[unavailable image: secret]"))
        assertFalse(rewritten.contains("<img"))
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
    fun externalNavigationRequiresExplicitUserActivation() {
        assertTrue(PreviewNavigationPolicy.opensExternally("https://example.com/image.png", userActivated = true))
        assertTrue(PreviewNavigationPolicy.opensExternally("mailto:hello@example.com", userActivated = true))
        assertFalse(PreviewNavigationPolicy.opensExternally("https://example.com/image.png", userActivated = false))
        assertFalse(PreviewNavigationPolicy.opensExternally("javascript:alert(1)", userActivated = true))
        assertFalse(PreviewNavigationPolicy.opensExternally("file:///secret", userActivated = true))
    }
}
