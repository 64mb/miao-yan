package com.tw93.miaoyan.android

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditorScrollInstrumentedTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(EditorScrollTestActivity::class.java)

    @Test
    fun fastSwipeContinuesWithNativeFlingAfterFingerIsReleased() {
        var offsetAtRelease = 0
        activityRule.scenario.onActivity { activity ->
            val editor = activity.editor
            assertTrue("Test document must exceed the viewport", editor.canScrollVertically(1))

            val x = editor.width / 2f
            val top = editor.height * .82f
            val bottom = editor.height * .18f
            val startedAt = SystemClock.uptimeMillis()
            val events = listOf(
                motionEvent(startedAt, startedAt, MotionEvent.ACTION_DOWN, x, top),
                motionEvent(startedAt, startedAt + 12, MotionEvent.ACTION_MOVE, x, editor.height * .65f),
                motionEvent(startedAt, startedAt + 24, MotionEvent.ACTION_MOVE, x, editor.height * .42f),
                motionEvent(startedAt, startedAt + 36, MotionEvent.ACTION_MOVE, x, bottom),
                motionEvent(startedAt, startedAt + 44, MotionEvent.ACTION_UP, x, bottom),
            )
            events.forEach { event ->
                editor.dispatchTouchEvent(event)
                event.recycle()
            }
            offsetAtRelease = editor.scrollY
        }

        assertTrue("Swipe must move the editor before release", offsetAtRelease > 0)
        SystemClock.sleep(240)

        activityRule.scenario.onActivity { activity ->
            assertTrue(
                "Editor must keep moving with fling velocity after ACTION_UP",
                activity.editor.scrollY > offsetAtRelease + 24,
            )
        }
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
}
