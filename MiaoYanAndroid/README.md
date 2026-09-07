# MiaoYan Android prototype

Native Android 15+ prototype for validating the first local-library flow:

1. Select exactly one library root through Storage Access Framework.
2. Persist access to that tree and recursively list `.md`, `.markdown`, and `.txt` notes.
3. Search and open a note.
4. Edit through a platform `EditText` that does not replace text during IME composition.
5. Preview GitHub Flavored Markdown through the official cmark-gfm native library, with JavaScript, raw HTML, frames, and network loads disabled.
6. Save only if the document hash still matches the opened version, then verify the written bytes.

The user-selected SAF tree remains canonical. There is no broad storage permission and no network permission.

## Build

```bash
cd MiaoYanAndroid
./gradlew test lint assembleDebug
```

The project pins AGP 9.4.0, Gradle 9.6.0, Kotlin/Compose compiler 2.4.10, Compose BOM 2026.06.00, NDK 29.0.14206865, CMake 3.22.1, `compileSdk 36`, `targetSdk 36`, and `minSdk 35`. The prototype deliberately stays on the newest SDK platform currently available from the installed stable Android SDK channel; moving to API 37 is a dependency-only follow-up once that platform is available locally.

cmark-gfm 0.29.0.gfm.13 is vendored from GitHub at commit `587a12bb54d95ac37241377e6ddc93ea0e45439b`; its source archive checksum and update procedure are recorded in `app/src/main/cpp/third_party/cmark-gfm/README.miaoyan.md`. Builds do not fetch native source from the network.

The renderer enables the table, strikethrough, autolink, tagfilter, and task-list extensions. It does not pass `CMARK_OPT_UNSAFE`: raw HTML (including iframes) is omitted by cmark before reaching WebView. Remote Markdown images are changed to inert placeholders by the single post-render content-policy boundary. CSP and `blockNetworkLoads` independently prevent automatic remote media loading. The boundary intentionally does not yet decide between click-to-load and opening media externally.

## Deliberate prototype limits

- Local inline images, note creation/rename/Trash, Room indexing, and Git sync are not included in this first executable slice. Android AI is explicitly out of scope.
- Native APKs are currently produced for `arm64-v8a` devices and `x86_64` emulators only.
- SAF save recovery is best effort. The production journal and crash recovery state machine remain Phase 0 work.
