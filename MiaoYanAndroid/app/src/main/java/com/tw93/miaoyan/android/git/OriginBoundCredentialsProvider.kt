package com.tw93.miaoyan.android.git

import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/** Refuses even to answer credential prompts outside the configured HTTPS host and port. */
internal class OriginBoundCredentialsProvider(
    repositoryUrl: String,
    credentials: GitCredentials,
) : CredentialsProvider() {
    private val origin = GitOrigin.fromHttpsUrl(repositoryUrl)
    private val delegate = UsernamePasswordCredentialsProvider(
        credentials.username,
        credentials.personalAccessToken,
    )

    override fun isInteractive(): Boolean = false

    override fun supports(vararg items: CredentialItem): Boolean = delegate.supports(*items)

    override fun get(uri: URIish, vararg items: CredentialItem): Boolean =
        origin.matches(uri) && delegate.get(uri, *items)
}
