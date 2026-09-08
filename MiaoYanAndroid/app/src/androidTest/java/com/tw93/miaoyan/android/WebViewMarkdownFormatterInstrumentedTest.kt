package com.tw93.miaoyan.android

import androidx.test.platform.app.InstrumentationRegistry
import com.tw93.miaoyan.android.typesetting.WebViewMarkdownFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewMarkdownFormatterInstrumentedTest {
    @Test
    fun formatsOfflineAndPreservesProtectedSyntax() = runBlocking {
        val formatter = WebViewMarkdownFormatter(InstrumentationRegistry.getInstrumentation().targetContext)
        val markdown = """
            ---
            title: "Keep:   exact"
            ---
            # Heading

            * first
            * protected `x  =  1`, ${'$'}a_b + c${'$'}, and [[Note Name|Alias]]

            ```kotlin
            val   value=1
            ```

            <div data-label="a  b">
            raw   HTML
            </div>

            [site](https://example.com/a_(b)?q=x  "Title")
        """.trimIndent()

        try {
            val formatted = formatter.format(markdown)

            assertTrue(formatted.contains("- first"))
            assertTrue(formatted.startsWith("---\ntitle: \"Keep:   exact\"\n---"))
            assertTrue(formatted.contains("`x  =  1`"))
            assertTrue(formatted.contains("${'$'}a_b + c${'$'}"))
            assertTrue(formatted.contains("[[Note Name|Alias]]"))
            assertTrue(formatted.contains("```kotlin\nval   value=1\n```"))
            assertTrue(formatted.contains("<div data-label=\"a  b\">\nraw   HTML\n</div>"))
            assertTrue(formatted.contains("(https://example.com/a_(b)?q=x  \"Title\")"))
            assertFalse(formatted.endsWith('\n'))
        } finally {
            formatter.close()
        }
    }
}
