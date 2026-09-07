package com.tw93.miaoyan.android.git

import android.util.Log
import com.tw93.miaoyan.android.BuildConfig

internal object GitSyncDiagnostics {
    private const val Tag = "MiaoYanGitSync"

    fun <T> run(operation: String, vararg sensitiveValues: String, block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) {
            // Do not hand Throwable directly to Log: transport exceptions can contain the remote
            // URL. Render and redact the complete chain and stacks before it reaches logcat.
            Log.e(Tag, safeDiagnostic(operation, error, sensitiveValues.asList()))
        }
        throw mapForUser(error)
    }

    fun safeDiagnostic(
        operation: String,
        error: Throwable,
        sensitiveValues: List<String> = emptyList(),
    ): String {
        val rendered = buildString {
            append("Git ").append(operation).append(" failed")
            generateSequence(error) { it.cause }.forEachIndexed { index, cause ->
                append('\n')
                if (index > 0) append("caused by ")
                append(cause::class.java.name)
                cause.message?.takeIf(String::isNotBlank)?.let { message ->
                    append(": ").append(message)
                }
                cause.stackTrace.forEach { frame ->
                    append("\n  at ").append(frame)
                }
            }
        }
        return sensitiveValues.asSequence()
            .filter(String::isNotBlank)
            .fold(rendered) { safe, value -> safe.replace(value, "<redacted>") }
            .replace(HttpsUrl, "https://<redacted>")
            .replace(HttpAuthorization, "$1<redacted>")
            .replace(CredentialQuery, "$1<redacted>")
    }

    fun mapForUser(error: Throwable): GitSyncException = when (error) {
        is GitSyncException -> error
        else -> GitSyncException.Storage(
            "Git sync could not prepare the local repository. Your local notes were kept unchanged.",
            error,
        )
    }

    private val HttpsUrl = Regex("https://[^\\s)\\]}]+", RegexOption.IGNORE_CASE)
    private val HttpAuthorization = Regex(
        "(authorization\\s*[:=]\\s*(?:basic|bearer)?\\s*)[^\\s,;]+",
        RegexOption.IGNORE_CASE,
    )
    private val CredentialQuery = Regex(
        "([?&](?:token|access_token|pat|password)=)[^&\\s]+",
        RegexOption.IGNORE_CASE,
    )
}
