package com.tw93.miaoyan.android.ui.presentation

import com.tw93.miaoyan.android.data.EditorSettings
import com.tw93.miaoyan.android.data.LocalImagePolicy
import com.tw93.miaoyan.android.data.normalizedEditorFontSize
import com.tw93.miaoyan.android.ui.MarkdownRenderer
import com.tw93.miaoyan.android.ui.theme.MiaoYanColors
import java.security.SecureRandom
import java.util.Base64

enum class PresentationMode {
    ContinuousPreview,
    Slides,
}

/** Builds inert preview HTML and Reveal sections from the shared native cmark-gfm renderer. */
object PresentationDocument {
    const val AssetOrigin = "https://appassets.androidplatform.net"

    fun renderContinuous(
        markdown: String,
        darkMode: Boolean,
        editorSettings: EditorSettings = EditorSettings(),
    ): String = renderContinuous(
        markdown,
        darkMode,
        editorSettings,
        fragmentRenderer = MarkdownRenderer::renderFragment,
    )

    internal fun renderContinuous(
        markdown: String,
        darkMode: Boolean,
        editorSettings: EditorSettings = EditorSettings(),
        nonce: String = createNonce(),
        fragmentRenderer: (String) -> String,
    ): String {
        require(Nonce.matches(nonce)) { "CSP nonce must be URL-safe base64." }
        val fragment = fragmentRenderer(MarkdownRenderer.stripFrontmatter(markdown))
        val colors = PresentationColors(darkMode, editorSettings)
        return """
            <!doctype html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; font-src $AssetOrigin; img-src $AssetOrigin data:; media-src 'none'; frame-src https:; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'">
            <link rel="icon" href="data:,">
            ${colors.fontPreload}
            <style>${continuousStyle(colors)}</style>
            </head><body>$fragment
            <script nonce="$nonce">
              (() => {
                'use strict';
                document.addEventListener('click', event => {
                  const button = event.target.closest('button.embed-placeholder[data-embed]');
                  if (!button) return;
                  let url;
                  try { url = new URL(button.dataset.embed); } catch (_) { return; }
                  if (url.protocol !== 'https:') return;
                  const frame = document.createElement('iframe');
                  frame.src = url.href;
                  frame.setAttribute('sandbox', '');
                  frame.setAttribute('referrerpolicy', 'no-referrer');
                  frame.setAttribute('loading', 'lazy');
                  frame.setAttribute('title', 'Embedded content');
                  button.replaceWith(frame);
                });
              })();
            </script>
            </body></html>
        """.trimIndent()
    }

    fun renderSlides(
        markdown: String,
        darkMode: Boolean,
        initialSlide: Int,
        editorSettings: EditorSettings = EditorSettings(),
    ): String = renderSlides(
        markdown,
        darkMode,
        initialSlide,
        editorSettings,
        createNonce(),
        MarkdownRenderer::renderFragment,
    )

    internal fun renderSlides(
        markdown: String,
        darkMode: Boolean,
        initialSlide: Int,
        editorSettings: EditorSettings = EditorSettings(),
        nonce: String,
        fragmentRenderer: (String) -> String,
    ): String {
        require(Nonce.matches(nonce)) { "CSP nonce must be URL-safe base64." }
        val slides = split(markdown)
        val start = initialSlide.coerceIn(0, slides.lastIndex)
        val sections = slides.joinToString("\n") { slide ->
            val fragment = fragmentRenderer(slide)
            "<section>$fragment</section>"
        }
        val colors = PresentationColors(darkMode, editorSettings)
        return """
            <!doctype html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce' 'strict-dynamic'; style-src 'unsafe-inline' $AssetOrigin; font-src $AssetOrigin; img-src $AssetOrigin data:; media-src 'none'; frame-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'">
            <link rel="icon" href="data:,">
            ${colors.fontPreload}
            <link nonce="$nonce" rel="stylesheet" href="$AssetOrigin/presentation/reveal.css">
            <style nonce="$nonce">${slideStyle(colors)}</style>
            </head><body>
            <div class="reveal"><div class="slides">$sections</div></div>
            <script nonce="$nonce" src="$AssetOrigin/presentation/reveal.js"></script>
            <script nonce="$nonce">
              (() => {
                'use strict';
                const report = (index) => {
                  window.location.href = 'miaoyan-slide://state/' + index;
                };
                Reveal.on('slidechanged', event => report(event.indexh));
                Reveal.initialize({
                  controls: true,
                  progress: true,
                  history: false,
                  hash: false,
                  keyboard: true,
                  touch: true,
                  overview: false,
                  help: false,
                  center: true,
                  transition: 'slide',
                  backgroundTransition: 'fade'
                }).then(() => {
                  Reveal.slide($start);
                  report(Reveal.getIndices().h || 0);
                  document.body.focus();
                });
              })();
            </script>
            </body></html>
        """.trimIndent()
    }

    /** Only an exact three-hyphen line is a horizontal slide boundary. */
    fun split(markdown: String): List<String> {
        val content = MarkdownRenderer.stripFrontmatter(markdown)
        val slides = mutableListOf<StringBuilder>(StringBuilder())
        content.lineSequence().forEach { line ->
            if (line == "---") {
                slides += StringBuilder()
            } else {
                if (slides.last().isNotEmpty()) slides.last().append('\n')
                slides.last().append(line)
            }
        }
        return slides.map(StringBuilder::toString)
    }

    private fun createNonce(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun continuousStyle(colors: PresentationColors): String = """
        :root { color-scheme: ${colors.scheme}; }
        ${colors.fontFace}
        * { box-sizing: border-box; }
        html, body { margin: 0; max-width: 100%; min-height: 100%; overflow-x: hidden; background: ${colors.background}; color: ${colors.foreground}; }
        body { padding: max(24px, env(safe-area-inset-top)) max(20px, env(safe-area-inset-right)) max(52px, env(safe-area-inset-bottom)) max(20px, env(safe-area-inset-left)); font-family: ${colors.fontStack}; font-size: ${colors.fontSize}px; line-height: 1.74; letter-spacing: .04em; overflow-wrap: anywhere; }
        body > * { max-width: min(860px, 100%); margin-left: auto; margin-right: auto; }
        h1,h2,h3,h4,h5,h6 { line-height: 1.25; margin-top: 1.35em; margin-bottom: .55em; }
        h1 { font-size: 2em; } h2 { font-size: 1.55em; }
        p { margin-top: .8em; margin-bottom: .8em; } a { color: ${colors.link}; }
        code { background: ${colors.code}; border-radius: 5px; padding: .12em .35em; font-family: ui-monospace, monospace; }
        pre { max-width: 100%; overflow-x: hidden; background: ${colors.code}; border-radius: 8px; padding: 12px 16px; white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word; }
        pre code { padding: 0; } blockquote { margin-left: 0; padding-left: 14px; border-left: 3px solid ${colors.border}; color: ${colors.muted}; }
        img,video,audio,iframe,table { max-width: 100%; }
        img { height: auto; display: block; margin: 1em auto; }
        hr { border: 0; border-top: 1px solid ${colors.border}; margin: 2em 0; }
        ul,ol { padding-left: 1.5em; }
        table { border-collapse: collapse; table-layout: fixed; width: 100%; }
        th,td { border: 1px solid ${colors.border}; overflow-wrap: anywhere; padding: .35em .65em; }
        .media-placeholder { display: inline-block; max-width: 100%; color: ${colors.muted}; font-style: italic; overflow-wrap: anywhere; }
        .embed-placeholder { display: block; max-width: 100%; margin: 1em auto; padding: .65em .9em; color: ${colors.link}; background: transparent; border: 1px solid ${colors.border}; border-radius: 8px; font: inherit; cursor: pointer; }
        iframe { display: block; width: 100%; min-height: min(62vh, 640px); margin: 1em auto; border: 1px solid ${colors.border}; border-radius: 8px; }
    """.trimIndent()

    private fun slideStyle(colors: PresentationColors): String = """
        :root { color-scheme: ${colors.scheme}; }
        ${colors.fontFace}
        html, body, .reveal-viewport { margin: 0; width: 100%; height: 100%; overflow: hidden; background: ${colors.background}; color: ${colors.foreground}; }
        .reveal { color: ${colors.foreground}; font-family: ${colors.fontStack}; font-size: ${colors.slideFontSize}px; }
        .reveal .slides { text-align: left; }
        .reveal .slides section { max-height: 100%; overflow-x: hidden; overflow-y: auto; overflow-wrap: anywhere; padding: 10px; box-sizing: border-box; }
        .reveal h1,.reveal h2,.reveal h3,.reveal h4,.reveal h5,.reveal h6 { color: ${colors.foreground}; line-height: 1.18; text-transform: none; }
        .reveal a { color: ${colors.link}; }
        .reveal code { background: ${colors.code}; border-radius: 5px; padding: .1em .3em; }
        .reveal pre { width: 100%; max-width: 100%; overflow: auto; background: ${colors.code}; border-radius: 10px; padding: 14px; box-sizing: border-box; }
        .reveal pre code { max-height: 50vh; padding: 0; }
        .reveal blockquote { width: auto; margin-left: 0; padding-left: 18px; border-left: 4px solid ${colors.border}; color: ${colors.muted}; box-shadow: none; }
        .reveal img,.reveal video,.reveal audio,.reveal iframe,.reveal table { max-width: 100%; }
        .reveal img { max-height: 58vh; object-fit: contain; }
        .reveal table { border-collapse: collapse; table-layout: fixed; width: 100%; }
        .reveal th,.reveal td { border: 1px solid ${colors.border}; overflow-wrap: anywhere; padding: .3em .55em; }
        .reveal .controls { color: ${colors.link}; }
        .reveal .progress { color: ${colors.link}; }
        .media-placeholder { color: ${colors.muted}; font-style: italic; }
        @media (max-width: 600px), (max-height: 500px) { .reveal { font-size: ${colors.compactSlideFontSize}px; } }
    """.trimIndent()

    private data class PresentationColors(
        val darkMode: Boolean,
        val editorSettings: EditorSettings,
    ) {
        val scheme = if (darkMode) "dark" else "light"
        val background = if (darkMode) MiaoYanColors.PreviewBackgroundDarkCss else MiaoYanColors.PreviewBackgroundLightCss
        val foreground = if (darkMode) MiaoYanColors.PreviewTextDarkCss else MiaoYanColors.PreviewTextLightCss
        val muted = if (darkMode) MiaoYanColors.PreviewSecondaryDarkCss else MiaoYanColors.PreviewSecondaryLightCss
        val link = if (darkMode) MiaoYanColors.PreviewLinkDarkCss else MiaoYanColors.PreviewLinkLightCss
        val border = if (darkMode) MiaoYanColors.PreviewBorderDarkCss else MiaoYanColors.PreviewBorderLightCss
        val code = if (darkMode) MiaoYanColors.PreviewCodeDarkCss else MiaoYanColors.PreviewCodeLightCss
        val fontSize = normalizedEditorFontSize(editorSettings.fontSizeSp)
        val fontStack = editorSettings.font.cssStack
        val slideFontSize = (fontSize * 2.375f).toInt()
        val compactSlideFontSize = fontSize * 2
        val usesBundledFont = editorSettings.font == com.tw93.miaoyan.android.data.EditorFont.JETBRAINS_MONO
        val fontPreload = if (usesBundledFont) {
            "<link rel=\"preload\" href=\"$AssetOrigin/presentation/jetbrains-mono.ttf\" as=\"font\" type=\"font/ttf\" crossorigin>"
        } else {
            ""
        }
        val fontFace = if (usesBundledFont) {
            "@font-face { font-family: 'JetBrains Mono'; src: url('$AssetOrigin/presentation/jetbrains-mono.ttf') format('truetype'); font-style: normal; font-weight: 400; font-display: block; }"
        } else {
            ""
        }
    }

    private val Nonce = Regex("[A-Za-z0-9_-]{16,64}")
}

/** URL policy shared by the HTML rewriter and the native request handler. */
object PresentationAssetPolicy {
    fun rewriteLocalImages(fragment: String): String = Image.replace(fragment) { match ->
        val source = decodeHtmlAttribute(match.groupValues[2])
        when (val classified = LocalImagePolicy.classifyMarkdownSource(source)) {
            is LocalImagePolicy.MarkdownSource.Local ->
                match.groupValues[1] + classified.assetUrl + match.groupValues[3]
            is LocalImagePolicy.MarkdownSource.External -> match.value
            LocalImagePolicy.MarkdownSource.Unsupported -> if (source.startsWith("/i/")) {
                "<span class=\"media-placeholder\">Local image unavailable</span>"
            } else {
                match.value
            }
        }
    }

    fun fileNameForAssetUrl(rawUrl: String): String? = LocalImagePolicy.fileNameForAssetUrl(rawUrl)

    private fun decodeHtmlAttribute(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private val Image = Regex("""(<img\s+[^>]*\bsrc=")([^"]+)("[^>]*>)""", RegexOption.IGNORE_CASE)
}

object SlideStateNavigation {
    fun reportedIndex(rawUrl: String, hasUserGesture: Boolean): Int? {
        if (hasUserGesture) return null
        val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return null
        if (uri.scheme != "miaoyan-slide" || uri.rawAuthority != "state" || uri.rawQuery != null || uri.rawFragment != null) {
            return null
        }
        return uri.path.removePrefix("/").takeIf { it.matches(Regex("0|[1-9][0-9]{0,5}")) }?.toIntOrNull()
    }
}
