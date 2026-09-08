# Android presentation subsystem

`ui/presentation/` owns two full-window, read-only surfaces. `ContinuousPreview`
is one scrollable cmark-gfm document and uses the video-camera glyph only as a
view-mode affordance. `Slides` splits on exact `---` lines and places separately
rendered cmark-gfm fragments into Reveal.js sections.

Reveal.js 4.3.1 core JavaScript and CSS come from the pinned macOS bundle under
`assets/presentation/`; their MIT license ships beside them. The JavaScript copy
has only its three legacy dynamic-`Function` fallbacks replaced with equivalent
CSP-safe behavior, so the slide runtime does not require `unsafe-eval`. The WebView
serves only those bundled files and the synthetic appassets image origin.
JavaScript is enabled for Slides and for the continuous preview's small nonce-scoped
iframe-activation/readiness script. Slides permit only the bundled Reveal runtime and
never permit network frames. Continuous preview permits a validated HTTPS frame only
after an explicit tap and creates it with an empty sandbox. File/content access, ambient
storage, popups, forms, scripts inside the frame, and top navigation remain disabled.

Active slides are top-anchored scroll containers sized to the WebView viewport. Their
bottom padding includes the Android safe area and Reveal controls, so a long slide can
always scroll its final line fully into view instead of clipping it below the viewport.

## Integration points

`MiaoYanApp.kt` exposes two separate toolbar actions, creates a saveable
`PresentationSession`, and replaces app chrome with `PresentationHost` while a
mode is active. A toolbar redesign should preserve both actions and pass the
current draft into that host. Back calls `PresentationSession.exit()`.

`PresentationImageHandler` is the storage boundary. The app-private library
creates a `LocalImagePolicy.NoteAssetScope` rooted at
`filesDir/libraries/default`, and `AppPrivatePresentationImageHandler` delegates
the request to `LocalFileImageLoader`. Keep the canonical-root, symlink,
single-segment URL, and MIME checks in those shared data-layer types; never
expose an actual file path to WebView.

The subsystem needs no manifest permission. In particular, the camera glyph
must never add a camera API/contract, `CAMERA`, `READ_MEDIA_*`, broad storage,
or `INTERNET` permission.
