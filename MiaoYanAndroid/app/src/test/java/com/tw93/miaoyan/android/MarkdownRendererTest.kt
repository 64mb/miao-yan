package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.ui.MarkdownRenderer
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
    fun escapesRawHtmlAndRejectsScriptLinks() {
        val html = MarkdownRenderer.renderFragment("<script>alert(1)</script>\n\n[bad](javascript:alert(1))")
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("href=\"javascript:"))
    }

    @Test
    fun rendersPrototypeMarkdownBlocks() {
        val html = MarkdownRenderer.renderFragment("# Title\n\n- [x] done\n- **strong**\n\n```swift\nlet x = 1\n```")
        assertTrue(html.contains("<h1>Title</h1>"))
        assertTrue(html.contains("checked"))
        assertTrue(html.contains("<strong>strong</strong>"))
        assertTrue(html.contains("class=\"language-swift\""))
    }
}
