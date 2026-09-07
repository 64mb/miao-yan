package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.ui.MarkdownRenderer
import com.tw93.miaoyan.android.ui.DeferredIframePolicy
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
    fun convertsOnlyStandaloneHttpsIframesOutsideCodeToDeferredTokens() {
        val prepared = DeferredIframePolicy.preprocess(
            """
                <iframe src="https://video.example/embed?id=1&amp;mode=clean" allow="camera"></iframe>
                ```html
                <iframe src="https://code.example"></iframe>
                ```
                <iframe src="http://insecure.example"></iframe>
            """.trimIndent(),
        )

        assertTrue(prepared.contains("https://appassets.androidplatform.net/embed/"))
        assertFalse(prepared.contains("allow=\"camera\""))
        assertTrue(prepared.contains("<iframe src=\"https://code.example\"></iframe>"))
        assertTrue(prepared.contains("[iframe blocked]"))
    }

    @Test
    fun deferredIframeTokenBecomesAnInertActivationButton() {
        val prepared = DeferredIframePolicy.preprocess(
            "<iframe src=\"https://video.example/embed?id=1&amp;mode=clean\"></iframe>",
        )
        val tokenUrl = Regex("https://appassets\\.androidplatform\\.net/embed/[A-Za-z0-9_-]+")
            .find(prepared)?.value.orEmpty()
        val token = tokenUrl.substringAfterLast('/')
        val rewritten = PreviewContentPolicy.rewrite(
            "<p><a href=\"$tokenUrl\">Embedded content — tap to load</a></p>",
        )

        assertEquals("https://video.example/embed?id=1&mode=clean", DeferredIframePolicy.urlForToken(token))
        assertTrue(rewritten.contains("button type=\"button\""))
        assertTrue(rewritten.contains("data-embed=\"https://video.example/embed?id=1&amp;mode=clean\""))
        assertFalse(rewritten.contains("<iframe"))
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
