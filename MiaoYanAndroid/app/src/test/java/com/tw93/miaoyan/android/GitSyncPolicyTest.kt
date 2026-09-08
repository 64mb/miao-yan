package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.git.GitContentGate
import com.tw93.miaoyan.android.git.GitConflictCodec
import com.tw93.miaoyan.android.git.GitConflictDetails
import com.tw93.miaoyan.android.git.GitConflictFile
import com.tw93.miaoyan.android.git.GitConflictKind
import com.tw93.miaoyan.android.git.GitCredentials
import com.tw93.miaoyan.android.git.GitSyncConfig
import com.tw93.miaoyan.android.git.GitSyncException
import com.tw93.miaoyan.android.git.GitSyncPathPolicy
import com.tw93.miaoyan.android.git.CredentialPayloadCodec
import com.tw93.miaoyan.android.git.OriginBoundCredentialsProvider
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.HttpConfig
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish

class GitSyncPolicyTest {
    @Test
    fun allowsNotesAndAttachmentTreesOnly() {
        assertTrue(GitSyncPathPolicy.isAllowed("Journal/today.md"))
        assertTrue(GitSyncPathPolicy.isAllowed("note.markdown"))
        assertTrue(GitSyncPathPolicy.isAllowed("Project/i/photo.webp"))
        assertTrue(GitSyncPathPolicy.isAllowed("files/archive/data.bin"))
        assertTrue(GitSyncPathPolicy.isAllowed(".gitignore"))
        assertFalse(GitSyncPathPolicy.isAllowed("README.pdf"))
        assertFalse(GitSyncPathPolicy.isAllowed(".git/config"))
        assertFalse(GitSyncPathPolicy.isAllowed("Trash/deleted.md"))
        assertFalse(GitSyncPathPolicy.isAllowed("../outside.md"))
        assertFalse(GitSyncPathPolicy.isAllowed("Images/photo.webp"))
        assertFalse(GitSyncPathPolicy.isAllowed("Folder/.gitignore"))
        assertFalse(GitSyncPathPolicy.isAllowed(".GitIgnore"))
    }

    @Test
    fun allowsOnlyTheAppManagedTrashShape() {
        val id = "92e5c30f-0c40-4a78-9ab6-52b58229b691"
        assertTrue(GitSyncPathPolicy.isAllowed(".Trash/manifest.v1"))
        assertFalse(GitSyncPathPolicy.isAllowed(".Trash/manifest.tsv"))
        assertTrue(GitSyncPathPolicy.isAllowed(".Trash/items/$id/Welcome.md"))
        assertTrue(GitSyncPathPolicy.isAllowed(".Trash/items/$id/Folder/Nested.md"))
        assertTrue(GitSyncPathPolicy.isAllowed(".Trash/items/$id/Folder/i/photo.webp"))
        assertTrue(GitSyncPathPolicy.isAttachment(".Trash/items/$id/Folder/i/photo.webp"))
        assertFalse(GitSyncPathPolicy.isAllowed(".Trash/other.md"))
        assertFalse(GitSyncPathPolicy.isAllowed(".Trash/items/not-a-uuid/Welcome.md"))
        assertFalse(GitSyncPathPolicy.isAllowed(".Trash/items/$id/.secret"))
        assertFalse(GitSyncPathPolicy.isAllowed(".Trash/items/$id/Folder/random.bin"))
        assertFalse(GitSyncPathPolicy.isAllowed("Trash/items/$id/Welcome.md"))
    }

    @Test
    fun acceptsOnlyCredentialFreeHttpsUrls() {
        val config = GitSyncConfig(
            repositoryUrl = "https://example.com/team/notes.git",
            authorName = "Miao Yan",
            authorEmail = "miao@example.com",
            periodicEnabled = true,
        ).validated()
        assertEquals("https://example.com/team/notes.git", config.repositoryUrl)
        assertThrows(GitSyncException.Configuration::class.java) {
            config.copy(repositoryUrl = "ssh://git@example.com/team/notes.git").validated()
        }
        assertThrows(GitSyncException.Configuration::class.java) {
            config.copy(repositoryUrl = "http://example.com/team/notes.git").validated()
        }
        assertThrows(GitSyncException.Configuration::class.java) {
            config.copy(repositoryUrl = "https://user:token@example.com/team/notes.git").validated()
        }
        assertThrows(GitSyncException.Configuration::class.java) {
            config.copy(repositoryUrl = "https://example.com/team/notes.git?token=secret").validated()
        }
    }

    @Test
    fun rejectsCredentialHeaderControls() {
        assertThrows(GitSyncException.Configuration::class.java) {
            GitCredentials("user\r\nInjected", "token").validated()
        }
        assertThrows(GitSyncException.Configuration::class.java) {
            GitCredentials("user", "token\nInjected").validated()
        }
    }

    @Test
    fun validatesCommitAuthorSeparatelyFromHttpsCredentials() {
        val config = GitSyncConfig(
            repositoryUrl = "https://example.com/notes.git",
            authorName = "  Miao Yan  ",
            authorEmail = "  author@example.com  ",
            periodicEnabled = false,
        ).validated()

        assertEquals("Miao Yan", config.authorName)
        assertEquals("author@example.com", config.authorEmail)
        assertThrows(GitSyncException.Configuration::class.java) {
            config.copy(authorName = " ").validated()
        }
        assertThrows(GitSyncException.Configuration::class.java) {
            config.copy(authorEmail = "not-an-email").validated()
        }
        assertEquals("https-user", GitCredentials("https-user", "pat").validated().username)
    }

    @Test
    fun encryptedPayloadCredentialsStayBoundToNormalizedRepositoryUrl() {
        val credentials = GitCredentials("miao", "secret-token")
        val normalized = GitSyncConfig(
            repositoryUrl = "HTTPS://EXAMPLE.COM:443/team/../notes.git",
            authorName = "Miao Yan",
            authorEmail = "miao@example.com",
            periodicEnabled = false,
        ).validated().repositoryUrl
        val stored = CredentialPayloadCodec.decode(
            CredentialPayloadCodec.encode(normalized, credentials),
        )

        assertEquals(credentials, stored.credentialsFor("https://example.com/notes.git"))
        assertEquals(null, stored.credentialsFor("https://example.com/other.git"))
        assertEquals(null, stored.credentialsFor("https://example.com:8443/notes.git"))
    }

    @Test
    fun credentialProviderAnswersOnlyForExactHttpsHostAndPort() {
        val provider = OriginBoundCredentialsProvider(
            "https://example.com/team/notes.git",
            GitCredentials("miao", "secret-token"),
        )
        fun request(uri: String): Boolean = provider.get(
            URIish(uri),
            CredentialItem.Username(),
            CredentialItem.Password(),
        )

        assertTrue(request("https://example.com/another/path.git"))
        assertTrue(request("https://EXAMPLE.COM:443/team/notes.git"))
        assertFalse(request("https://example.com:8443/team/notes.git"))
        assertFalse(request("https://mirror.example.com/team/notes.git"))
        assertFalse(request("http://example.com/team/notes.git"))
    }

    @Test
    fun jgitHttpPolicyDisablesRedirectsAndKeepsTlsVerification() {
        val config = Config().apply {
            setString("http", null, "followRedirects", "false")
            setInt("http", null, "maxRedirects", 0)
            setBoolean("http", null, "sslVerify", true)
        }
        val http = HttpConfig(config, URIish("https://example.com/team/notes.git"))
        assertEquals(HttpConfig.HttpRedirectMode.FALSE, http.followRedirects)
        assertEquals(0, http.maxRedirects)
        assertTrue(http.isSslVerify)
    }

    @Test
    fun enforcesPerFileLimitButAllowsLargerLibraries() {
        GitContentGate.validate(mapOf("one.md" to 12L, "i/photo.png" to 24L), "test")
        GitContentGate.validate(mapOf("large-note.md" to 80L * 1024L * 1024L), "test")
        GitContentGate.validate(mapOf("i/one.bin" to 20L * 1024L * 1024L, "files/two.bin" to 20L * 1024L * 1024L), "test")
        assertThrows(GitSyncException.Limit::class.java) {
            GitContentGate.validate(mapOf("i/large.bin" to 25L * 1024L * 1024L + 1L), "test")
        }
    }

    @Test
    fun rejectsCaseCollisions() {
        assertThrows(GitSyncException.Conflict::class.java) {
            GitContentGate.validate(mapOf("Note.md" to 1L, "note.md" to 1L), "test")
        }
    }

    @Test
    fun conflictStateRoundTripsWithoutPlainPathsInItsHeader() {
        val details = GitConflictDetails(
            localCommit = "a".repeat(40),
            remoteCommit = "b".repeat(40),
            files = listOf(
                GitConflictFile("Folder/note.md", 1_000L, 2_000L, localExists = true, remoteExists = false),
            ),
        )
        val encoded = GitConflictCodec.encode(details)
        assertFalse(encoded.contains("Folder/note.md"))
        assertEquals(details, GitConflictCodec.decode(encoded))
    }

    @Test
    fun unrelatedConflictKindRoundTrips() {
        val details = GitConflictDetails(
            localCommit = "c".repeat(40),
            remoteCommit = "d".repeat(40),
            files = listOf(
                GitConflictFile("local.md", 1_000L, null, localExists = true, remoteExists = false),
            ),
            kind = GitConflictKind.UnrelatedHistory,
        )

        assertEquals(details, GitConflictCodec.decode(GitConflictCodec.encode(details)))
    }

    @Test
    fun legacyConflictStateDefaultsToPerFileChoices() {
        val path = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("note.md".toByteArray(Charsets.UTF_8))
        val encoded = "${"e".repeat(40)}\t${"f".repeat(40)}\n$path\t1\t2\t1\t1\n"

        assertEquals(GitConflictKind.FileChoices, GitConflictCodec.decode(encoded)?.kind)
    }
}
