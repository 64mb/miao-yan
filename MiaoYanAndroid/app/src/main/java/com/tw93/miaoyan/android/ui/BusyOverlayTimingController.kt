package com.tw93.miaoyan.android.ui

import android.os.SystemClock

internal class BusyOverlayTimingController(
    val showDelayMillis: Long = 150L,
    val minimumVisibleMillis: Long = 800L,
) {
    init {
        require(showDelayMillis >= 0L)
        require(minimumVisibleMillis >= 0L)
    }
}

/**
 * Owns overlay timing independently from any one Compose effect. A busy-key change may cancel
 * the effect that was waiting for a deadline, so every new input first advances the old state to
 * the current monotonic time before applying that input.
 */
internal class BusyOverlayStateMachine(
    private val timing: BusyOverlayTimingController = BusyOverlayTimingController(),
    private val nowMillis: () -> Long = SystemClock::uptimeMillis,
) {
    private sealed interface Phase {
        data object Hidden : Phase

        data class WaitingToShow(val deadlineMillis: Long) : Phase

        data class Visible(val shownAtMillis: Long) : Phase
    }

    private var busy = false
    private var phase: Phase = Phase.Hidden
    private var lastNowMillis = Long.MIN_VALUE

    val isVisible: Boolean
        get() = phase is Phase.Visible

    fun updateBusy(value: Boolean): Long? {
        val now = monotonicNow()
        advance(now)
        busy = value
        when (phase) {
            Phase.Hidden -> if (busy) {
                phase = Phase.WaitingToShow(now.saturatedPlus(timing.showDelayMillis))
            }
            is Phase.WaitingToShow -> if (!busy) phase = Phase.Hidden
            is Phase.Visible -> Unit
        }
        advance(now)
        return delayUntilNextDeadline(now)
    }

    fun advanceToNextDeadline(): Long? {
        val now = monotonicNow()
        advance(now)
        return delayUntilNextDeadline(now)
    }

    private fun advance(now: Long) {
        when (val current = phase) {
            Phase.Hidden -> Unit
            is Phase.WaitingToShow -> if (busy && now >= current.deadlineMillis) {
                phase = Phase.Visible(now)
            }
            is Phase.Visible -> if (
                !busy && now >= current.shownAtMillis.saturatedPlus(timing.minimumVisibleMillis)
            ) {
                phase = Phase.Hidden
            }
        }
    }

    private fun delayUntilNextDeadline(now: Long): Long? = when (val current = phase) {
        Phase.Hidden -> null
        is Phase.WaitingToShow -> (current.deadlineMillis - now).coerceAtLeast(0L)
        is Phase.Visible -> if (busy) {
            null
        } else {
            (current.shownAtMillis.saturatedPlus(timing.minimumVisibleMillis) - now).coerceAtLeast(0L)
        }
    }

    private fun monotonicNow(): Long {
        val sampled = nowMillis()
        val monotonic = sampled.coerceAtLeast(lastNowMillis)
        lastNowMillis = monotonic
        return monotonic
    }

    private fun Long.saturatedPlus(delta: Long): Long =
        if (this > Long.MAX_VALUE - delta) Long.MAX_VALUE else this + delta
}
