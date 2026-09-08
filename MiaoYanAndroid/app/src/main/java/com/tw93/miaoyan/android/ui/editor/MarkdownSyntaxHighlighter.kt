package com.tw93.miaoyan.android.ui.editor

import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.BaseInputConnection

internal class MarkdownSyntaxColorSpan(color: Int) : ForegroundColorSpan(color)

/** Applies colour-only spans without changing Markdown text or its layout. */
object MarkdownSyntaxHighlighter {
    fun highlight(
        editable: Editable,
        palette: MarkdownSyntaxPalette,
        changedStart: Int,
        changedEndExclusive: Int,
        clearAll: Boolean,
    ): Boolean {
        if (BaseInputConnection.getComposingSpanStart(editable) >= 0) return false

        val range = MarkdownHighlightPolicy.resolveRange(editable, changedStart, changedEndExclusive)
        val clearStart = if (clearAll) 0 else range.start
        val clearEnd = if (clearAll) editable.length else range.endExclusive
        editable.getSpans(clearStart, clearEnd, MarkdownSyntaxColorSpan::class.java).forEach(editable::removeSpan)
        val source = editable.subSequence(range.start, range.endExclusive).toString()
        MarkdownSyntaxTokenizer.tokenize(source).forEach { token ->
            val start = range.start + token.start
            val end = range.start + token.endExclusive
            if (start < end && end <= editable.length) {
                editable.setSpan(
                    MarkdownSyntaxColorSpan(palette.colorFor(token.kind)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return true
    }
}
