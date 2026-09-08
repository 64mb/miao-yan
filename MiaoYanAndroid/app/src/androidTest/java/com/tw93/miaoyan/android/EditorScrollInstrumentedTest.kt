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
            val scrollView = activity.editorScrollView
            assertTrue("Test document must exceed the viewport", scrollView.canScrollVertically(1))

            val x = scrollView.width / 2f
            val top = scrollView.height * .82f
            val bottom = scrollView.height * .18f
            val startedAt = SystemClock.uptimeMillis()
            val events = listOf(
                motionEvent(startedAt, startedAt, MotionEvent.ACTION_DOWN, x, top),
                motionEvent(startedAt, startedAt + 12, MotionEvent.ACTION_MOVE, x, scrollView.height * .65f),
                motionEvent(startedAt, startedAt + 24, MotionEvent.ACTION_MOVE, x, scrollView.height * .42f),
                motionEvent(startedAt, startedAt + 36, MotionEvent.ACTION_MOVE, x, bottom),
                motionEvent(startedAt, startedAt + 44, MotionEvent.ACTION_UP, x, bottom),
            )
            events.forEach { event ->
                scrollView.dispatchTouchEvent(event)
                event.recycle()
            }
            offsetAtRelease = scrollView.scrollY
        }

        assertTrue("Swipe must move the editor before release", offsetAtRelease > 0)
        SystemClock.sleep(240)

        activityRule.scenario.onActivity { activity ->
            assertTrue(
                "Editor must keep moving with fling velocity after ACTION_UP",
                activity.editorScrollView.scrollY > offsetAtRelease + 24,
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
