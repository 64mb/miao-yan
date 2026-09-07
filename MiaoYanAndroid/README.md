# MiaoYan Android prototype

Native Android 15+ prototype for validating the first local-library flow. The canonical library
contract is `context.filesDir/libraries/default`; SAF is reserved for explicit Import/Export:

1. Recursively list `.md`, `.markdown`, and `.txt` notes from one app-private library.
2. Keep everyday preview and editing independent from `ContentResolver` and persisted SAF grants.
3. Search and open a note.
4. Edit through a platform `EditText` that does not replace text during IME composition.
5. Preview GitHub Flavored Markdown through the official cmark-gfm native library, with JavaScript,
   raw HTML, frames, and network loads disabled.
   Local `/i/<name>` images are streamed from the `i` directory next to the selected note through a
   restricted synthetic origin. Canonical-path checks keep the loader inside both the selected
   note's parent and the app-private root, rejecting traversal and symlink escape. External images
   remain explicit tap-to-open links and are never loaded automatically. External video and iframe
   markup stays inert; the shared policy requires user activation and a sandbox for future iframe
   embedding.
6. Save only if the document hash still matches the opened version, then verify the written bytes.
7. Follow the Android system light/dark appearance and keep editor/preview typography in local DataStore settings.

There is no broad storage permission and no network permission.

## Visual source of truth

The Android palette is copied from the macOS resources rather than approximated from a generic Material theme:

| Surface | Light | Dark |
| --- | --- | --- |
| Editor background (`mainBackground.colorset`) | `#FFFFFF` | `#23282D` |
| Editor text (`Theme.textColor`) | black at `216/255` alpha | white at `216/255` alpha |
| Markdown preview background/text | `#FFFFFF` / `#262626` | `#23282D` / `#E7E9EA` |
| Preview secondary/link/border/code | `#777777` / `#0C6ADA` / `#E6E6E6` / `#F7F7F7` | `#ABB2BF` / `#1D9BF0` / `#454545` / `#282E33` |

`Theme.textColor` is `NSColor.labelColor` in the default system appearance; on the two editor backgrounds it composites to approximately `#272727` and `#DDDEDF`. The storyboard fallback `mainText.colorset` is `#262626` / `#E8E8EB`, but the Markdown formatter uses `Theme.textColor` at runtime.

The macOS defaults in `FontConfiguration.swift` are PingFang SC Regular for editor, interface, and preview, Menlo for code, with 16 pt editor/preview text. Those Apple fonts are not redistributed. Android defaults to the platform sans stack (Roboto with the device's Noto/CJK fallbacks), uses platform serif/monospace as optional system choices, and bundles JetBrains Mono 2.304 as the only font file. JetBrains Mono is distributed unchanged under SIL Open Font License 1.1; the full license is bundled at `app/src/main/res/raw/jetbrains_mono_ofl.txt` and is readable from Settings.

## Build

```bash
cd MiaoYanAndroid
./gradlew test lint assembleDebug
```

The project pins AGP 9.4.0, Gradle 9.6.0, Kotlin/Compose compiler 2.4.10, Compose BOM 2026.06.00, NDK 29.0.14206865, CMake 3.22.1, `compileSdk 36`, `targetSdk 36`, and `minSdk 35`. The prototype deliberately stays on the newest SDK platform currently available from the installed stable Android SDK channel; moving to API 37 is a dependency-only follow-up once that platform is available locally.

cmark-gfm 0.29.0.gfm.13 is vendored from GitHub at commit `587a12bb54d95ac37241377e6ddc93ea0e45439b`; its source archive checksum and update procedure are recorded in `app/src/main/cpp/third_party/cmark-gfm/README.miaoyan.md`. Builds do not fetch native source from the network.

The renderer enables the table, strikethrough, autolink, tagfilter, and task-list extensions. It does not pass `CMARK_OPT_UNSAFE`: raw HTML (including iframes) is omitted by cmark before reaching WebView. The single post-render content-policy boundary maps valid local images to the synthetic origin and changes remote images to explicit external links. CSP and `blockNetworkLoads` independently prevent automatic remote media loading.

## Deliberate prototype limits

- Note creation/rename/Trash, Room indexing, Git sync, and the SAF transport behind the Settings Import/Export callbacks are not included in this slice. SAF never becomes the live-library root. Android AI is explicitly out of scope.
- Native APKs are currently produced for `arm64-v8a` devices and `x86_64` emulators only.
- The production atomic-write and crash-recovery state machine remains Phase 0 work.
