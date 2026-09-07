package com.tw93.miaoyan.android.ui

internal enum class LibraryWindowLayoutMode { SinglePane, ListDetail }

internal object LibraryWindowLayout {
    const val ListDetailMinimumWidthDp = 840

    fun forWidthDp(widthDp: Int): LibraryWindowLayoutMode =
        if (widthDp >= ListDetailMinimumWidthDp) {
            LibraryWindowLayoutMode.ListDetail
        } else {
            LibraryWindowLayoutMode.SinglePane
        }
}
