package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.data.index.IndexText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexTextTest {
    @Test
    fun buildsLiteralPrefixFtsQuery() {
        assertEquals("\"room*\" AND \"search*\"", IndexText.ftsQuery("room/search"))
        assertEquals("\"NEAR*\" AND \"drop*\"", IndexText.ftsQuery("NEAR \"drop\"*"))
        assertNull(IndexText.ftsQuery("[[ ]] --"))
    }

    @Test
    fun extractsNormalizedDeduplicatedWikilinks() {
        assertEquals(
            listOf("folder/Note.md" to "note", "Second#heading" to "second"),
            IndexText.extractWikilinks(
                "See [[ folder/Note.md |label]], [[NOTE]] and [[Second#heading]].",
            ).map { it.target to it.targetKey },
        )
    }

    @Test
    fun derivesTitleAndBoundedSnippet() {
        assertEquals("Daily note", IndexText.titleForPath("Journal/Daily note.md"))
        assertEquals("Heading prose", IndexText.snippet("# Heading\n\n**prose**"))
        assertEquals(240, IndexText.snippet("a".repeat(300)).length)
    }
}
