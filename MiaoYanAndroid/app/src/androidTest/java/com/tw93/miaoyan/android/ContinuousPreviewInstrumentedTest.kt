package com.tw93.miaoyan.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ContinuousPreviewInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ContinuousPreviewTestActivity>()

    @Test
    fun preloadsFinalFontOnceAndReusesWebViewAcrossFullscreen() {
        compose.waitUntil(timeoutMillis = 30_000) {
            val controller = ContinuousPreviewTestActivity.previewController
            controller.pageFinishedCount == 1 && controller.readyRevision != null
        }
        val controller = ContinuousPreviewTestActivity.previewController
        assertEquals(1, controller.htmlLoadCount)
        assertEquals(0, controller.firstVisibleFrameCount)

        // shouldInterceptRequest is not invoked when Chromium reuses a resource cached by an
        // earlier WebView test. Inspect the rendered document instead so the full suite proves
        // the final bundled font and local image are ready without depending on cache order.
        val resourcesReady = AtomicReference<String?>()
        compose.activityRule.scenario.onActivity { activity ->
            requireNotNull(findWebView(activity.window.decorView)).evaluateJavascript(
                """
                (() => {
                  return [
                    document.fonts.status,
                    document.fonts.check('16px "JetBrains Mono"'),
                    getComputedStyle(document.body).fontFamily,
                    Array.from(document.fonts).map(face => face.family + ':' + face.status).join(',')
                  ].join('|');
                })()
                """.trimIndent(),
            ) { value -> resourcesReady.set(value) }
        }
        compose.waitUntil(timeoutMillis = 5_000) { resourcesReady.get() != null }
        assertEquals("\"loaded|true|\\\"JetBrains Mono\\\", ui-monospace, monospace|JetBrains Mono:loaded\"", resourcesReady.get())

        compose.activityRule.scenario.onActivity { it.showInlinePreview() }
        compose.waitUntil(timeoutMillis = 5_000) { controller.firstVisibleFrameCount == 1 }
        assertEquals(1, controller.htmlLoadCount)

        val originalWebView = AtomicReference<WebView>()
        compose.activityRule.scenario.onActivity { activity ->
            originalWebView.set(requireNotNull(findWebView(activity.window.decorView)))
            originalWebView.get().scrollTo(0, 400)
            activity.setFullscreen(true)
        }
        compose.waitForIdle()

        compose.activityRule.scenario.onActivity { activity ->
            val fullscreenWebView = requireNotNull(findWebView(activity.window.decorView))
            assertTrue(fullscreenWebView.scrollY > 0)
            assertTrue(originalWebView.get() === fullscreenWebView)
            activity.setFullscreen(false)
        }
        compose.waitForIdle()
        assertEquals(1, controller.htmlLoadCount)
        assertEquals(1, controller.pageFinishedCount)
    }

    @Test
    fun iframeNeedsATapAndReceivesAnEmptyStrictSandbox() {
        compose.waitUntil(timeoutMillis = 30_000) {
            ContinuousPreviewTestActivity.previewController.readyRevision != null
        }
        compose.activityRule.scenario.onActivity { it.showInlinePreview() }
        val result = AtomicReference<String?>()
        compose.activityRule.scenario.onActivity { activity ->
            val webView = requireNotNull(findWebView(activity.window.decorView))
            webView.evaluateJavascript(
                """
                (() => {
                  const button = document.querySelector('button.embed-placeholder[data-embed]');
                  if (!button || document.querySelector('iframe')) return false;
                  button.click();
                  const frame = document.querySelector('iframe');
                  return !!frame && frame.hasAttribute('sandbox') && frame.getAttribute('sandbox') === '' &&
                    frame.getAttribute('referrerpolicy') === 'no-referrer' && !frame.hasAttribute('allow');
                })()
                """.trimIndent(),
            ) { value -> result.set(value) }
        }
        compose.waitUntil(timeoutMillis = 5_000) { result.get() != null }

        assertEquals("true", result.get())
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

/**
 * Renderer termination affects Android WebView's process-wide test state. Keep this class last
 * alphabetically so the destructive recovery check cannot poison another Compose/WebView host.
 */
@Suppress("DEPRECATION")
class ZRendererRecoveryInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ContinuousPreviewTestActivity>()

    @Test
    fun renderProcessTerminationRecreatesPreviewWithoutKillingActivity() {
        compose.waitUntil(timeoutMillis = 30_000) {
            ContinuousPreviewTestActivity.previewController.readyRevision != null
        }
        val controller = ContinuousPreviewTestActivity.previewController
        compose.activityRule.scenario.onActivity { it.showInlinePreview() }
        compose.waitUntil(timeoutMillis = 5_000) { controller.firstVisibleFrameCount == 1 }

        val terminatedWebView = AtomicReference<WebView>()
        compose.activityRule.scenario.onActivity { activity ->
            terminatedWebView.set(requireNotNull(findWebViewForRecovery(activity.window.decorView)))
            assertTrue(terminatedWebView.get().webViewRenderProcess?.terminate() == true)
        }
        compose.waitUntil(timeoutMillis = 30_000) {
            controller.htmlLoadCount == 2 && controller.pageFinishedCount == 2 && controller.readyRevision != null
        }

        compose.activityRule.scenario.onActivity { activity ->
            assertTrue(!activity.isFinishing)
            assertTrue(terminatedWebView.get() !== findWebViewForRecovery(activity.window.decorView))
        }
    }

    private fun findWebViewForRecovery(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        repeat(view.childCount) { index ->
            findWebViewForRecovery(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
