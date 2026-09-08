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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun tallSlideScrollsUntilItsLastLineIsInsideTheViewport() {
        compose.waitUntil(timeoutMillis = 30_000) { PresentationTestActivity.reportedSlide.get() == 0 }

        evaluateJavaScript("Reveal.slide(2)")
        compose.waitUntil(timeoutMillis = 30_000) { PresentationTestActivity.reportedSlide.get() == 2 }

        val metrics = evaluateJavaScript(
            """
            (() => {
              const slide = document.querySelector('section.present');
              slide.scrollTop = slide.scrollHeight;
              const last = slide.lastElementChild.getBoundingClientRect();
              const viewport = slide.getBoundingClientRect();
              return [slide.scrollTop, slide.scrollHeight, slide.clientHeight, last.top, last.bottom, viewport.top, viewport.bottom].join('|');
            })()
            """.trimIndent(),
        ).trim('"').split('|').map(String::toFloat)

        assertTrue("The fixture must be taller than one slide", metrics[1] > metrics[2])
        assertTrue("The slide must have scrolled", metrics[0] > 0f)
        assertTrue("The last line must start inside the slide: $metrics", metrics[3] >= metrics[5])
        assertTrue("The last line must end inside the slide: $metrics", metrics[4] <= metrics[6])
    }

    private fun evaluateJavaScript(script: String): String {
        val result = AtomicReference<String>()
        val completed = CountDownLatch(1)
        compose.activityRule.scenario.onActivity { activity ->
            requireNotNull(findWebView(activity.window.decorView)).evaluateJavascript(script) { value ->
                result.set(value)
                completed.countDown()
            }
        }
        assertTrue("JavaScript evaluation timed out", completed.await(10, TimeUnit.SECONDS))
        return requireNotNull(result.get())
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
