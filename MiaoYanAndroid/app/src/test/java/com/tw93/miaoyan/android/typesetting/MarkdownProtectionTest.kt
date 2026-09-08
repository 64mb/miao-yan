package com.tw93.miaoyan.android.typesetting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownProtectionTest {
    @Test
    fun restoresEveryProtectedMarkdownSurfaceExactly() {
        val markdown = """
            ---
            title: "Keep:   exact"
            ---
            # Heading

            * item with `x  =  1`, ${'$'}a_b + c${'$'}, and [[Note Name|Alias]]

            ```kotlin
            val   value=1
            ```

            <div data-label="a  b">
            raw   HTML
            </div>

            [inline](https://example.com/a_(b)?q=x  "Title")
            [reference]: https://example.com/ref  "Reference title"
        """.trimIndent()
        val protected = ProtectedMarkdown.protect(markdown)

        assertFalse(protected.source.contains("title: \"Keep:   exact\""))
        assertFalse(protected.source.contains("val   value=1"))
        assertFalse(protected.source.contains("raw   HTML"))
        assertFalse(protected.source.contains("https://example.com/a_(b)?q=x"))

        val simulatedPrettier = protected.source.replace("* item", "- item") + "\n"
        val restored = protected.restoreAndValidate(simulatedPrettier)

        assertTrue(restored.contains("- item"))
        assertFalse(restored.endsWith('\n'))
        assertEquals(ProtectedMarkdown.fragments(markdown), ProtectedMarkdown.fragments(restored))
        assertTrue(restored.contains("<div data-label=\"a  b\">\nraw   HTML\n</div>"))
    }

    @Test
    fun preservesCrLfAndTrailingNewlineConvention() {
        val markdown = "---\r\ntitle: exact\r\n---\r\n\r\n* item\r\n"
        val protected = ProtectedMarkdown.protect(markdown)

        val restored = protected.restoreAndValidate(protected.source.replace("* item", "- item") + "\n")

        assertFalse(restored.replace("\r\n", "").contains('\n'))
        assertTrue(restored.endsWith("\r\n"))
        assertTrue(restored.startsWith("---\r\ntitle: exact\r\n---"))
    }

    @Test
    fun failsClosedWhenFormatterDropsAPlaceholder() {
        val protected = ProtectedMarkdown.protect("Use `exact  code` here")
        val withoutProtectedCode = protected.source.replace(Regex("MiaoYanProtected\\w*"), "")

        assertThrows(MarkdownFormattingException::class.java) {
            protected.restoreAndValidate(withoutProtectedCode)
        }
    }

    @Test
    fun appliesOnlyToMatchingOwnerAndDraftRevision() {
        val request = TypesettingRequest(ownerNoteId = "note-a", draftRevision = 42, markdown = "* item")

        assertTrue(TypesettingResultGuard.canApply(request, "note-a", 42))
        assertFalse(TypesettingResultGuard.canApply(request, "note-b", 42))
        assertFalse(TypesettingResultGuard.canApply(request, "note-a", 43))
        assertFalse(TypesettingResultGuard.canApply(request, null, 42))
    }
}
