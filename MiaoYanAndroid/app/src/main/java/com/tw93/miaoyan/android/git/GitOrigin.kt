package com.tw93.miaoyan.android.git

import java.net.IDN
import java.net.URI
import java.util.Locale
import org.eclipse.jgit.transport.URIish

/** Exact network identity to which a stored username and PAT may be disclosed. */
internal data class GitOrigin(val host: String, val port: Int) {
    fun matches(uri: URIish): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            normalizeHost(uri.host) == host && effectivePort(uri.port) == port

    companion object {
        fun fromHttpsUrl(repositoryUrl: String): GitOrigin {
            val uri = validatedHttpsUri(repositoryUrl)
            return GitOrigin(normalizeHost(uri.host), effectivePort(uri.port))
        }

        fun normalizeHttpsRepositoryUrl(repositoryUrl: String): String {
            val uri = validatedHttpsUri(repositoryUrl.trim()).normalize()
            val host = normalizeHost(uri.host)
            val formattedHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
            val port = uri.port.takeUnless { it == -1 || it == 443 }?.let { ":$it" }.orEmpty()
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }
            return URI("https://$formattedHost$port$path").toASCIIString()
        }

        private fun validatedHttpsUri(repositoryUrl: String): URI {
            val uri = runCatching { URI(repositoryUrl) }.getOrNull()
                ?: throw GitSyncException.Configuration("Enter a valid HTTPS repository URL.")
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
                throw GitSyncException.Configuration("Only HTTPS Git repository URLs are supported.")
            }
            if (uri.userInfo != null || uri.fragment != null || uri.query != null) {
                throw GitSyncException.Configuration(
                    "The repository URL cannot contain credentials, a query, or a fragment.",
                )
            }
            if (uri.port != -1 && uri.port !in 1..65_535) {
                throw GitSyncException.Configuration("Enter a valid HTTPS repository port.")
            }
            return uri
        }

        private fun normalizeHost(host: String?): String =
            host?.let(IDN::toASCII)?.lowercase(Locale.ROOT).orEmpty()

        private fun effectivePort(port: Int): Int = if (port == -1) 443 else port
    }
}
