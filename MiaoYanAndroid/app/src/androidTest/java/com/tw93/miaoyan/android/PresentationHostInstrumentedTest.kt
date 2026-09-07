package com.tw93.miaoyan.android

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class PresentationHostInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<PresentationTestActivity>()

    @Test
    fun bundledRevealHandlesKeyboardAndRetainsSlideAcrossRecreation() {
        compose.waitUntil(timeoutMillis = 30_000) { PresentationTestActivity.reportedSlide.get() == 0 }
        compose.waitUntil(timeoutMillis = 5_000) {
            val focused = AtomicBoolean(false)
            compose.activityRule.scenario.onActivity { activity ->
                focused.set(findWebView(activity.window.decorView)?.hasFocus() == true)
            }
            focused.get()
        }
        compose.activityRule.scenario.onActivity { activity ->
            val webView = requireNotNull(findWebView(activity.window.decorView))
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        compose.waitUntil(timeoutMillis = 30_000) { PresentationTestActivity.reportedSlide.get() == 1 }

        compose.activityRule.scenario.recreate()

        compose.waitUntil(timeoutMillis = 30_000) { PresentationTestActivity.reportedSlide.get() == 1 }
        assertEquals(1, PresentationTestActivity.reportedSlide.get())
    }

    @Test
    fun backExitsThePresentationHost() {
        compose.waitUntil(timeoutMillis = 30_000) { PresentationTestActivity.reportedSlide.get() == 0 }

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

        compose.onNodeWithText("Presentation closed").assertIsDisplayed()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        repeat(view.childCount) { index ->
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
