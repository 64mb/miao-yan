package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.ui.presentation.PresentationDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationRendererInstrumentedTest {
    @Test
    fun rendersEachRevealSectionThroughNativeCmarkGfm() {
        val markdown = """
            | old | new |
            | --- | --- |
            | no | yes |
            ---
            - [x] shipped
        """.trimIndent()

        val html = PresentationDocument.renderSlides(markdown, darkMode = false, initialSlide = 0)

        assertEquals(2, "<section>".toRegex().findAll(html).count())
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("type=\"checkbox\" checked=\"\" disabled=\"\""))
    }

    @Test
    fun noteHtmlCannotAddExecutablePresentationContent() {
        val html = PresentationDocument.renderSlides(
            "<script>alert('no')</script>\n\n<iframe src=\"https://example.com\"></iframe>",
            darkMode = true,
            initialSlide = 0,
        )

        assertTrue(html.contains("raw HTML omitted"))
        assertFalse(html.contains("alert('no')"))
        assertFalse(html.contains("<iframe"))
    }
}
