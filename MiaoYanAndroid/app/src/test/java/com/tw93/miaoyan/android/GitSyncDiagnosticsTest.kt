package com.tw93.miaoyan.android

import com.tw93.miaoyan.android.git.GitSyncDiagnostics
import com.tw93.miaoyan.android.git.GitSyncException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class GitSyncDiagnosticsTest {
    @Test
    fun diagnosticIncludesEntireCauseChainButRedactsRemoteAndCredentials() {
        val root = IllegalStateException(
            "request https://user:secret@example.com/team/notes.git?token=secret-token",
            IllegalArgumentException("Authorization: Bearer secret-token"),
        )

        val diagnostic = GitSyncDiagnostics.safeDiagnostic(
            "sync",
            root,
            sensitiveValues = listOf("secret-token"),
        )

        assertTrue(diagnostic.contains("java.lang.IllegalStateException"))
        assertTrue(diagnostic.contains("java.lang.IllegalArgumentException"))
        assertFalse(diagnostic.contains("example.com"))
        assertFalse(diagnostic.contains("secret-token"))
        assertFalse(diagnostic.contains("user:secret"))
    }

    @Test
    fun unexpectedJgitFailureGetsStableRecoverableUserMessage() {
        val cause = IllegalStateException("Cannot read tree {}")
        val mapped = GitSyncDiagnostics.mapForUser(cause)

        assertTrue(mapped is GitSyncException.Storage)
        assertTrue(mapped.message.orEmpty().contains("local notes were kept unchanged"))
        assertSame(cause, mapped.cause)
    }

    @Test
    fun intentionalGitErrorsKeepTheirSpecificUserMessage() {
        val expected = GitSyncException.Configuration("Configure Git sync first.")
        assertSame(expected, GitSyncDiagnostics.mapForUser(expected))
    }

    @Test
    fun nonFatalErrorFromJgitIsMappedInsteadOfLeakingItsObfuscatedName() {
        val internal = Error("lb0")

        val mapped = GitSyncDiagnostics.mapForUser(internal)

        assertFalse(mapped.message.orEmpty().contains("lb0"))
        assertSame(internal, mapped.cause)
    }

    @Test
    fun cancellationAndVirtualMachineErrorsAreNeverConvertedToUserMessages() {
        val cancellation = CancellationException("cancelled")
        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
                GitSyncDiagnostics.mapForUser(cancellation)
            },
        )

        val fatal = OutOfMemoryError("fatal")
        assertSame(
            fatal,
            assertThrows(OutOfMemoryError::class.java) {
                GitSyncDiagnostics.mapForUser(fatal)
            },
        )
    }
}
