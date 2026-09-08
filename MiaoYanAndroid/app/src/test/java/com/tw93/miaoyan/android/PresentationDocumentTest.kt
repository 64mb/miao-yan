package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.ui.presentation.PresentationAssetPolicy
import com.tw93.miaoyan.android.ui.presentation.PresentationDocument
import com.tw93.miaoyan.android.ui.presentation.PreviewReadinessNavigation
import com.tw93.miaoyan.android.ui.presentation.SlideStateNavigation
import com.tw93.miaoyan.android.data.EditorFont
import com.tw93.miaoyan.android.data.EditorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationDocumentTest {
    @Test
    fun splitsOnlyExactThreeHyphenLinesAfterFrontmatter() {
        val markdown = """
            ---
            title: Deck
            ---
            # One
            ---
            # Two
             ---
            text---text
            ----
        """.trimIndent()

        assertEquals(
            listOf("# One", "# Two\n ---\ntext---text\n----"),
            PresentationDocument.split(markdown),
        )
    }

    @Test
    fun supportsCrlfSeparatorsAndKeepsEmptySlides() {
        assertEquals(listOf("one", "", "three"), PresentationDocument.split("one\r\n---\r\n---\r\nthree"))
    }

    @Test
    fun rendersEverySlideWithTheNativePipelineBoundary() {
        val inputs = mutableListOf<String>()
        val html = PresentationDocument.renderSlides(
            markdown = "first\n---\nsecond",
            darkMode = false,
            initialSlide = 50,
            nonce = "abcdefghijklmnop",
        ) { slide ->
            inputs += slide
            "<p>$slide</p>"
        }

        assertEquals(listOf("first", "second"), inputs)
        assertEquals(2, "<section>".toRegex().findAll(html).count())
        assertTrue(html.contains("Reveal.slide(1)"))
    }

    @Test
    fun slideDocumentAllowsOnlyNonceScriptsAndSyntheticAssets() {
        val html = PresentationDocument.renderSlides(
            markdown = "# Safe",
            darkMode = true,
            initialSlide = 0,
            nonce = "abcdefghijklmnop",
        ) { "<h1>Safe</h1>" }

        assertTrue(html.contains("script-src 'nonce-abcdefghijklmnop' 'strict-dynamic'"))
        assertTrue(html.contains("connect-src 'none'"))
        assertTrue(html.contains("frame-src 'none'"))
        assertTrue(html.contains("base-uri 'none'"))
        assertTrue(html.contains("https://appassets.androidplatform.net/presentation/reveal.js"))
        assertFalse(html.contains("plugin/markdown"))
        assertFalse(html.contains("http://"))
    }

    @Test
    fun continuousPreviewIsOneScrollableDocumentWithOnlyTheSandboxActivationScript() {
        var renderCount = 0
        val html = PresentationDocument.renderContinuous("# One\n---\n# Two", darkMode = false) {
            renderCount += 1
            "<h1>One</h1><hr /><h1>Two</h1>"
        }

        assertEquals(1, renderCount)
        assertTrue(html.contains("script-src 'nonce-"))
        assertTrue(html.contains("frame-src https:"))
        assertTrue(html.contains("frame.setAttribute('sandbox', '')"))
        assertFalse(html.contains("allow-scripts"))
        assertFalse(html.contains("allow-forms"))
        assertFalse(html.contains("allow-popups"))
        assertFalse(html.contains("allow-top-navigation"))
        assertTrue(html.contains("overflow-x: hidden"))
        assertTrue(html.contains("<hr />"))
        assertFalse(html.contains("class=\"reveal\""))
        assertFalse(html.contains("<section>"))
    }

    @Test
    fun continuousPreviewInstallsSafeHeadingAnchorNavigation() {
        val html = PresentationDocument.renderContinuous(
            "[Jump](#hello-world)\n\n# Hello, World!",
            darkMode = false,
        ) { "<p><a href=\"#hello-world\">Jump</a></p><h1>Hello, World!</h1>" }

        assertTrue(html.contains("document.querySelectorAll('h1,h2,h3,h4,h5,h6')"))
        assertTrue(html.contains(".replace(/[^\\p{L}\\p{M}\\p{N}\\s_-]/gu, '')"))
        assertTrue(html.contains("event.target instanceof Element"))
        assertTrue(html.contains("event.target.closest('a[href^=\"#\"]')"))
        assertTrue(html.contains("target.scrollIntoView({ block: 'start' })"))
        assertTrue(html.contains("history.replaceState(null, '', '#' + encodeURIComponent(targetId))"))
        assertTrue(html.contains("scroll-margin-top: 16px"))
    }

    @Test
    fun usesApprovedPreviewTokensAndCurrentEditorTypography() {
        val html = PresentationDocument.renderContinuous(
            markdown = "text",
            darkMode = true,
            editorSettings = EditorSettings(EditorFont.SYSTEM_SERIF, 24),
        ) { "<p>text</p>" }

        assertTrue(html.contains("background: #23282D"))
        assertTrue(html.contains("color: #E7E9EA"))
        assertTrue(html.contains("font-family: serif"))
        assertTrue(html.contains("font-size: 24px"))
        assertFalse(html.contains("#fffdfa", ignoreCase = true))
    }

    @Test
    fun previewAndSlidesUseMacPaletteAndReadableCodeTypography() {
        val markdown = """
            # Heading

            **Strong**

            ```kotlin
            val answer = 42 // highlighted
            ```
        """.trimIndent()
        val rendered = """
            <h1>Heading</h1>
            <p><strong>Strong</strong></p>
            <pre><code class="language-kotlin">val answer = 42 // highlighted
            </code></pre>
        """.trimIndent()
        val continuous = PresentationDocument.renderContinuous(markdown, darkMode = true) { rendered }
        val slides = PresentationDocument.renderSlides(
            markdown = markdown,
            darkMode = true,
            initialSlide = 0,
            editorSettings = EditorSettings(),
            nonce = "abcdefghijklmnop",
        ) { rendered }

        listOf(continuous, slides).forEach { html ->
            assertTrue(html.contains("#A178FF"))
            assertTrue(html.contains("#9B79F7"))
            assertTrue(html.contains("#8FFCCD"))
            assertTrue(html.contains("querySelectorAll('pre > code')"))
            assertTrue(html.contains("class=\"language-kotlin\""))
        }
        assertTrue(continuous.contains("line-height: 1.55"))
        assertTrue(slides.contains(".reveal p,.reveal li { line-height: 1.5; }"))
        assertTrue(slides.contains("max-height: 360px"))
        assertTrue(slides.contains("line-height: 1.45"))
        assertTrue(slides.contains("center: false"))
        assertTrue(slides.contains(".reveal .slides > section.present"))
        assertTrue(slides.contains("scroll-padding-bottom: max(96px"))
    }

    @Test
    fun bundledFontIsFinalBeforeTheDocumentCanBecomeVisible() {
        val html = PresentationDocument.renderContinuous(
            markdown = "text",
            darkMode = false,
            editorSettings = EditorSettings(EditorFont.JETBRAINS_MONO, 16),
        ) { "<p>text</p>" }

        val fontUrl = "https://appassets.androidplatform.net/presentation/jetbrains-mono.ttf"
        assertTrue(html.contains("font-src https://appassets.androidplatform.net"))
        assertTrue(html.contains("rel=\"preload\" href=\"$fontUrl\""))
        assertTrue(html.contains("src: url('$fontUrl')"))
        assertTrue(html.contains("font-display: block"))
        assertTrue(html.contains("rel=\"icon\" href=\"data:,\""))
        assertFalse(html.contains("font/ttf;base64"))
        assertEquals(1, "@font-face".toRegex().findAll(html).count())
    }

    @Test
    fun rewritesOnlyValidatedLocalImagesToTheSyntheticOrigin() {
        val rewritten = PresentationAssetPolicy.rewriteLocalImages(
            "<p><img src=\"/i/%E7%8C%AB%20photo%2B1.png\" alt=\"cat\" /></p>",
        )

        assertTrue(rewritten.contains("https://appassets.androidplatform.net/i/%E7%8C%AB%20photo%2B1.png"))
        assertEquals("猫 photo+1.png", PresentationAssetPolicy.fileNameForAssetUrl(
            "https://appassets.androidplatform.net/i/%E7%8C%AB%20photo%2B1.png",
        ))
    }

    @Test
    fun rejectsTraversalAndAmbiguousOrigins() {
        val rejected = listOf(
            "https://appassets.androidplatform.net/i/../secret.png",
            "https://appassets.androidplatform.net/i/%2e%2e",
            "https://appassets.androidplatform.net/i/folder%2Fsecret.png",
            "https://appassets.androidplatform.net/i/%252e%252e.png",
            "https://appassets.androidplatform.net.evil/i/photo.png",
        )
        rejected.forEach { assertNull(it, PresentationAssetPolicy.fileNameForAssetUrl(it)) }
    }

    @Test
    fun acceptsOnlyNonGestureSlideStateReports() {
        assertEquals(42, SlideStateNavigation.reportedIndex("miaoyan-slide://state/42", hasUserGesture = false))
        assertNull(SlideStateNavigation.reportedIndex("miaoyan-slide://state/42", hasUserGesture = true))
        assertNull(SlideStateNavigation.reportedIndex("miaoyan-slide://other/42", hasUserGesture = false))
        assertNull(SlideStateNavigation.reportedIndex("https://state/42", hasUserGesture = false))
    }

    @Test
    fun acceptsOnlyTheExactNonGesturePreviewReadinessReport() {
        assertTrue(PreviewReadinessNavigation.isReady("miaoyan-preview://ready", hasUserGesture = false))
        assertFalse(PreviewReadinessNavigation.isReady("miaoyan-preview://ready", hasUserGesture = true))
        assertFalse(PreviewReadinessNavigation.isReady("miaoyan-preview://ready/path", hasUserGesture = false))
        assertFalse(PreviewReadinessNavigation.isReady("miaoyan-preview://ready?value=1", hasUserGesture = false))
        assertFalse(PreviewReadinessNavigation.isReady("https://ready", hasUserGesture = false))
    }
}
