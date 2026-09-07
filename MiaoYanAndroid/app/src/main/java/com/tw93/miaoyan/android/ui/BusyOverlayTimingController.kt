package com.tw93.miaoyan.android.ui

internal class BusyOverlayTimingController(
    val showDelayMillis: Long = 150L,
    val minimumVisibleMillis: Long = 800L,
) {
    fun delayBeforeShow(alreadyVisible: Boolean): Long = if (alreadyVisible) 0L else showDelayMillis

    fun delayBeforeHide(shownAtMillis: Long, nowMillis: Long): Long =
        (shownAtMillis + minimumVisibleMillis - nowMillis).coerceAtLeast(0L)
}
