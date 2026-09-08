package com.tw93.miaoyan.android.ui

import com.tw93.miaoyan.android.data.AttachmentKind

data class DraftSnapshot(
    val ownerNoteId: String,
    val revision: Long,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

data class AttachmentRequest(
    val ownerNoteId: String,
    val draftRevision: Long,
    val selectionStart: Int,
    val selectionEnd: Int,
    val kind: AttachmentKind,
)

sealed interface AttachmentInsertionResult {
    data class Applied(val text: String, val cursor: Int) : AttachmentInsertionResult

    data object Stale : AttachmentInsertionResult
}

object AttachmentInsertionPolicy {
    fun request(snapshot: DraftSnapshot, kind: AttachmentKind): AttachmentRequest {
        require(snapshot.selectionStart in 0..snapshot.text.length && snapshot.selectionEnd in 0..snapshot.text.length)
        return AttachmentRequest(
            ownerNoteId = snapshot.ownerNoteId,
            draftRevision = snapshot.revision,
            selectionStart = minOf(snapshot.selectionStart, snapshot.selectionEnd),
            selectionEnd = maxOf(snapshot.selectionStart, snapshot.selectionEnd),
            kind = kind,
        )
    }

    fun apply(
        current: DraftSnapshot,
        request: AttachmentRequest,
        markdown: String,
    ): AttachmentInsertionResult {
        if (current.ownerNoteId != request.ownerNoteId || current.revision != request.draftRevision) {
            return AttachmentInsertionResult.Stale
        }
        if (request.selectionStart !in 0..current.text.length || request.selectionEnd !in 0..current.text.length) {
            return AttachmentInsertionResult.Stale
        }
        val updated = current.text.replaceRange(request.selectionStart, request.selectionEnd, markdown)
        return AttachmentInsertionResult.Applied(updated, request.selectionStart + markdown.length)
    }
}
