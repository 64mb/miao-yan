package com.tw93.miaoyan.android.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * URL and canonical-path policy for note-local images in the app-private library.
 *
 * A request can name only one direct child of the selected note's sibling `i`
 * directory. Canonical containment checks keep the lookup inside both that
 * parent and `filesDir/libraries/default`, including through symlinks.
 */
object LocalImagePolicy {
    const val AssetHost = "appassets.androidplatform.net"
    const val AssetOrigin = "https://$AssetHost"

    sealed interface MarkdownSource {
        data class Local(val fileName: String, val assetUrl: String) : MarkdownSource

        data class External(val url: String) : MarkdownSource

        data object Unsupported : MarkdownSource
    }

    enum class ExternalMediaKind {
        Image,
        Video,
        Iframe,
    }

    /** A remote embed candidate that must remain inert until explicitly activated. */
    data class DeferredExternalMedia(
        val url: String,
        val kind: ExternalMediaKind,
        val requiresSandbox: Boolean,
    )

    /** Identity used to prevent a WebView image session from crossing note-parent boundaries. */
    data class NoteAssetScope(
        val canonicalRootPath: String,
        val canonicalParentPath: String,
    )

    fun resolveNoteAssetScope(canonicalRoot: File, noteRelativePath: String): NoteAssetScope? {
        if (!NotePathPolicy.isNote(noteRelativePath)) return null
        val root = canonicalFile(canonicalRoot) ?: return null
        if (!root.isDirectory) return null
        val note = canonicalFile(File(root, noteRelativePath)) ?: return null
        if (!note.isFile || !isWithin(note, root)) return null
        val parent = note.parentFile ?: return null
        if (!isWithin(parent, root)) return null
        return NoteAssetScope(root.path, parent.path)
    }

    fun resolveLocalImage(scope: NoteAssetScope, fileName: String): File? {
        if (decodeFileName(encodePathSegment(fileName)) != fileName) return null
        val root = canonicalFile(File(scope.canonicalRootPath)) ?: return null
        val parent = canonicalFile(File(scope.canonicalParentPath)) ?: return null
        if (root.path != scope.canonicalRootPath || parent.path != scope.canonicalParentPath) return null
        if (!root.isDirectory || !parent.isDirectory || !isWithin(parent, root)) return null

        val imageDirectory = canonicalFile(File(parent, ImageDirectoryName)) ?: return null
        if (!imageDirectory.isDirectory || imageDirectory.parentFile != parent) return null
        if (!isWithin(imageDirectory, root)) return null

        val image = canonicalFile(File(imageDirectory, fileName)) ?: return null
        if (!image.isFile || image.parentFile != imageDirectory) return null
        return image.takeIf { isWithin(it, root) }
    }

    fun classifyMarkdownSource(rawSource: String): MarkdownSource {
        val source = rawSource.trim()
        if (source.startsWith("/i/")) {
            val fileName = decodeFileName(source.removePrefix("/i/")) ?: return MarkdownSource.Unsupported
            return MarkdownSource.Local(fileName, "$AssetOrigin/i/${encodePathSegment(fileName)}")
        }

        val external = deferredExternalMedia(source, ExternalMediaKind.Image)
            ?: return MarkdownSource.Unsupported
        return MarkdownSource.External(external.url)
    }

    fun deferredExternalMedia(rawUrl: String, kind: ExternalMediaKind): DeferredExternalMedia? {
        val source = rawUrl.trim()
        val url = if (source.startsWith("//")) "https:$source" else source
        val uri = parseUri(url) ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http")) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return DeferredExternalMedia(
            url = url,
            kind = kind,
            requiresSandbox = kind == ExternalMediaKind.Iframe,
        )
    }

    /**
     * The future click-to-load embed path must call this at activation time.
     * An iframe additionally needs a sandboxed host before its URL may be loaded.
     */
    fun canActivateExternalMedia(
        media: DeferredExternalMedia,
        userActivated: Boolean,
        sandboxed: Boolean,
    ): Boolean = userActivated && (!media.requiresSandbox || sandboxed)

    fun isAllowedExternalNavigation(rawUrl: String): Boolean {
        val uri = parseUri(rawUrl) ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme == "mailto") return uri.schemeSpecificPart.isNotBlank()
        return scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    fun fileNameForAssetUrl(rawUrl: String): String? {
        val uri = parseUri(rawUrl) ?: return null
        if (uri.scheme != "https" || uri.host != AssetHost) return null
        if (uri.rawAuthority != AssetHost || uri.rawQuery != null || uri.rawFragment != null) return null
        val rawPath = uri.rawPath ?: return null
        if (!rawPath.startsWith("/i/")) return null
        return decodeFileName(rawPath.removePrefix("/i/"))
    }

    fun mimeTypeFor(fileName: String, providerMimeType: String?): String? {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        val extensionMimeType = MimeTypesByExtension[extension]
        val providerMime = providerMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.let(MimeAliases::get)
            ?: providerMimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)

        if (providerMime in GenericMimeTypes) return extensionMimeType
        if (providerMime !in AllowedMimeTypes) return null
        if (extensionMimeType != null && extensionMimeType != providerMime) return null
        return providerMime
    }

    private fun decodeFileName(rawSegment: String): String? {
        if (rawSegment.isEmpty() || rawSegment.length > MaximumEncodedNameLength) return null
        if ('/' in rawSegment || '\\' in rawSegment || '?' in rawSegment || '#' in rawSegment) return null
        val decoded = decodePercentEscapes(rawSegment) ?: return null
        if (decoded.isEmpty() || decoded.length > MaximumFileNameLength) return null
        if (decoded == "." || decoded == ".." || '/' in decoded || '\\' in decoded) return null
        if (decoded.any { it.isISOControl() }) return null
        // Reject a second encoded layer instead of allowing ambiguous path-like names.
        if (EncodedByte.containsMatchIn(decoded)) return null
        return decoded
    }

    private fun decodePercentEscapes(value: String): String? = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            if (value[index] != '%') {
                append(value[index])
                index += 1
                continue
            }

            val bytes = ByteArrayOutputStream()
            while (index < value.length && value[index] == '%') {
                if (index + 2 >= value.length) return null
                val byte = value.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                bytes.write(byte)
                index += 3
            }
            val decoded = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            }.getOrNull() ?: return null
            append(decoded)
        }
    }

    private fun encodePathSegment(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            if (
                unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
                unsigned in '0'.code..'9'.code || unsigned in setOf('-'.code, '.'.code, '_'.code, '~'.code)
            ) {
                append(unsigned.toChar())
            } else {
                append('%')
                append(HexDigits[unsigned ushr 4])
                append(HexDigits[unsigned and 0x0f])
            }
        }
    }

    private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()

    private fun canonicalFile(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    private fun isWithin(candidate: File, root: File): Boolean = candidate.toPath().startsWith(root.toPath())

    private const val MaximumFileNameLength = 255
    private const val MaximumEncodedNameLength = MaximumFileNameLength * 3
    private const val HexDigits = "0123456789ABCDEF"
    private val EncodedByte = Regex("%[0-9a-fA-F]{2}")
    private val MimeTypesByExtension = mapOf(
        "png" to "image/png",
        "apng" to "image/apng",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
    )
    private val AllowedMimeTypes = MimeTypesByExtension.values.toSet()
    private val GenericMimeTypes = setOf(null, "", "application/octet-stream", "image/*")
    private val MimeAliases = mapOf(
        "image/jpg" to "image/jpeg",
        "image/pjpeg" to "image/jpeg",
        "image/x-png" to "image/png",
        "image/x-ms-bmp" to "image/bmp",
    )
    private const val ImageDirectoryName = "i"
}
