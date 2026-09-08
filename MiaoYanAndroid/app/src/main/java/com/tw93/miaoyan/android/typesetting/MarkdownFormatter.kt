package com.tw93.miaoyan.android.typesetting

import java.io.Closeable

/** Formats Markdown entirely inside the app process without file or network access. */
interface MarkdownFormatter : Closeable {
    suspend fun format(markdown: String): String
}

internal data class TypesettingRequest(
    val ownerNoteId: String,
    val draftRevision: Long,
    val markdown: String,
)

internal object TypesettingResultGuard {
    fun canApply(
        request: TypesettingRequest,
        currentOwnerNoteId: String?,
        currentDraftRevision: Long,
    ): Boolean =
        currentOwnerNoteId == request.ownerNoteId && currentDraftRevision == request.draftRevision
}

class MarkdownFormattingException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
