package com.tw93.miaoyan.android.data.index

import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.OpenNote
import java.util.Locale

data class IndexedDocument(
    val note: LibraryNote,
    val title: String,
    val titleKey: String,
    val snippet: String,
    val body: String,
    val contentHash: String,
    val links: List<WikilinkMetadata>,
)

data class WikilinkMetadata(val target: String, val targetKey: String)

object IndexText {
    private val searchToken = Regex("[\\p{L}\\p{N}_]+")
    private val wikilink = Regex("\\[\\[([^\\[\\]\\n]+)]]")
    private val markdownNoise = Regex("[`#>*_~|]+")
    private val whitespace = Regex("\\s+")

    fun document(openNote: OpenNote): IndexedDocument {
        val title = titleForPath(openNote.note.relativePath)
        return IndexedDocument(
            note = openNote.note,
            title = title,
            titleKey = normalizeTitle(title),
            snippet = snippet(openNote.text),
            body = openNote.text,
            contentHash = openNote.contentHash,
            links = extractWikilinks(openNote.text),
        )
    }

    fun titleForPath(relativePath: String): String {
        val name = relativePath.substringAfterLast('/')
        return name.substringBeforeLast('.', missingDelimiterValue = name)
    }

    fun normalizeTitle(rawTitle: String): String {
        val leaf = rawTitle.substringAfterLast('/').substringBefore('#').trim()
        val withoutExtension = when (leaf.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)) {
            "md", "markdown", "txt" -> leaf.substringBeforeLast('.')
            else -> leaf
        }
        return withoutExtension.lowercase(Locale.ROOT)
    }

    fun snippet(body: String): String = markdownNoise.replace(body, " ")
        .replace(whitespace, " ")
        .trim()
        .take(MaximumSnippetLength)

    fun extractWikilinks(body: String): List<WikilinkMetadata> = wikilink.findAll(body)
        .mapNotNull { match ->
            val target = match.groupValues[1].substringBefore('|').trim()
            val key = normalizeTitle(target)
            if (target.isEmpty() || key.isEmpty()) null else WikilinkMetadata(target, key)
        }
        .distinctBy(WikilinkMetadata::targetKey)
        .toList()

    /** Builds a literal, prefix-enabled FTS expression without exposing FTS operators. */
    fun ftsQuery(rawQuery: String): String? {
        val tokens = searchToken.findAll(rawQuery)
            .map { it.value.take(MaximumSearchTokenLength) }
            .filter { it.isNotEmpty() }
            .take(MaximumSearchTokens)
            .toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" AND ") { token -> "\"$token*\"" }
    }

    private const val MaximumSnippetLength = 240
    private const val MaximumSearchTokens = 12
    private const val MaximumSearchTokenLength = 64
}
