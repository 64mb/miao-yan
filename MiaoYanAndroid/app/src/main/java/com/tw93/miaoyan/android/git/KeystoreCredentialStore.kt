package com.tw93.miaoyan.android.git

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCredentialStore(context: Context) {
    private val credentialFile = File(context.noBackupFilesDir, CredentialRelativePath)

    fun save(repositoryUrl: String, credentials: GitCredentials) {
        val normalizedUrl = GitOrigin.normalizeHttpsRepositoryUrl(repositoryUrl)
        val plaintext = CredentialPayloadCodec.encode(normalizedUrl, credentials.validated())
        try {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext)
            val payload = ByteBuffer.allocate(1 + 1 + cipher.iv.size + ciphertext.size)
                .put(FormatVersion)
                .put(cipher.iv.size.toByte())
                .put(cipher.iv)
                .put(ciphertext)
                .array()
            atomicWrite(payload)
        } finally {
            plaintext.fill(0)
        }
    }

    fun load(repositoryUrl: String): GitCredentials? {
        if (!credentialFile.isFile) return null
        return try {
            val payload = credentialFile.readBytes()
            require(payload.size <= MaximumPayloadBytes)
            val buffer = ByteBuffer.wrap(payload)
            if (buffer.get() != FormatVersion) error("Unsupported credential format.")
            val ivLength = buffer.get().toInt() and 0xff
            require(ivLength in 12..32 && buffer.remaining() > ivLength)
            val iv = ByteArray(ivLength).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                CredentialPayloadCodec.decode(plaintext).credentialsFor(repositoryUrl)
            } finally {
                plaintext.fill(0)
            }
        } catch (error: GitSyncException.Configuration) {
            throw error
        } catch (error: Throwable) {
            throw GitSyncException.Configuration(
                "Stored Git credentials could not be decrypted. Enter them again.",
            )
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KeyStoreName).apply { load(null) }
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeyStoreName)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun atomicWrite(bytes: ByteArray) {
        val parent = credentialFile.parentFile ?: error("Credential directory is unavailable.")
        check(parent.isDirectory || parent.mkdirs()) { "Could not create credential storage." }
        val temporary = File(parent, ".credentials-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                credentialFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val CredentialRelativePath = "git/credentials.v1"
        const val KeyStoreName = "AndroidKeyStore"
        const val KeyAlias = "miaoyan.git.https.credentials.v1"
        const val Transformation = "AES/GCM/NoPadding"
        const val MaximumPayloadBytes = 32 * 1024
        const val FormatVersion: Byte = 1
    }
}

internal data class BoundGitCredentials(
    val repositoryUrl: String,
    val credentials: GitCredentials,
) {
    fun credentialsFor(candidateUrl: String): GitCredentials? =
        credentials.takeIf {
            GitOrigin.normalizeHttpsRepositoryUrl(candidateUrl) == repositoryUrl
        }
}

internal object CredentialPayloadCodec {
    fun encode(repositoryUrl: String, credentials: GitCredentials): ByteArray {
        val url = repositoryUrl.toByteArray(Charsets.UTF_8)
        val username = credentials.username.toByteArray(Charsets.UTF_8)
        val token = credentials.personalAccessToken.toByteArray(Charsets.UTF_8)
        require(url.size <= 8_192 && username.size <= 65_535 && token.size <= 65_535)
        return try {
            ByteBuffer.allocate(8 + url.size + username.size + token.size)
                .putInt(url.size)
                .put(url)
                .putShort(username.size.toShort())
                .put(username)
                .putShort(token.size.toShort())
                .put(token)
                .array()
        } finally {
            url.fill(0)
            username.fill(0)
            token.fill(0)
        }
    }

    fun decode(bytes: ByteArray): BoundGitCredentials {
        val buffer = ByteBuffer.wrap(bytes)
        val urlLength = buffer.int
        require(urlLength in 1..8_192 && urlLength <= buffer.remaining() - 4)
        val url = ByteArray(urlLength).also(buffer::get)
        val usernameLength = buffer.short.toInt() and 0xffff
        require(usernameLength <= buffer.remaining() - 2)
        val username = ByteArray(usernameLength).also(buffer::get)
        val tokenLength = buffer.short.toInt() and 0xffff
        require(tokenLength == buffer.remaining())
        val token = ByteArray(tokenLength).also(buffer::get)
        return try {
            val normalizedUrl = GitOrigin.normalizeHttpsRepositoryUrl(url.toString(Charsets.UTF_8))
            BoundGitCredentials(
                normalizedUrl,
                GitCredentials(
                    username.toString(Charsets.UTF_8),
                    token.toString(Charsets.UTF_8),
                ).validated(),
            )
        } finally {
            url.fill(0)
            username.fill(0)
            token.fill(0)
        }
    }
}
