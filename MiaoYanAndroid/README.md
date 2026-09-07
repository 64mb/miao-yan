# MiaoYan Android prototype

Native Android 15+ prototype for validating the first local-library flow. The canonical library
contract is `context.filesDir/libraries/default`; SAF is reserved for explicit Import/Export:

1. Recursively list nested `.md`, `.markdown`, and `.txt` notes while excluding `.git`,
   `Trash`/`.Trash`, `i`, `files`, hidden folders, and symlinks.
2. Create and rename notes with strict path and case/Unicode collision checks; save by expected
   content hash with atomic replacement.
3. Move notes into a recoverable app-private `.Trash` and restore them to the original folder or
   a safe root fallback. Settings owns the full-window Trash manager; permanent deletion requires
   a named destructive confirmation and stays inside the validated Trash item.
4. Keep everyday preview and editing independent from `ContentResolver`. SAF trees are used only
   by explicit Import Library and Export Library actions and never become a live root.
5. Search titles, nested paths, and bodies through a rebuildable Room FTS4 projection. Wikilinks
   and backlinks update transactionally; pins stay authoritative in DataStore.
6. Edit through a platform `EditText` that does not replace text during IME composition.
7. Preview GitHub Flavored Markdown through the official cmark-gfm native library, with JavaScript,
   raw HTML, frames, and network loads disabled.
   Local `/i/<name>` images are streamed from the `i` directory next to the selected note through a
   restricted synthetic origin. Canonical-path checks keep the loader inside both the selected
   note's parent and the app-private root, rejecting traversal and symlink escape. External images
   remain explicit tap-to-open links and are never loaded automatically. External video and iframe
   markup stays inert; the shared policy requires user activation and a sandbox for future iframe
   embedding.
8. Keep Auto/System, Dark, or Light appearance plus editor/preview typography in local DataStore
   settings. The selected theme applies consistently to app chrome, editor, preview, and presentation.
9. Enter a view-only fullscreen continuous Preview from the video-camera action, or a separate
   Reveal.js slide Presentation split by exact `---` lines.
10. Format Markdown with pinned Prettier in an isolated local WebView; protected
   syntax and a note-owner/draft-revision guard make the operation fail closed.
11. Sync the same app-private working tree with an explicitly configured HTTPS `origin/main`,
   without replacing the filesystem repository, Room search/backlinks, pins, attachments,
   typesetting, presentation, or SAF transfer flows.

Every canonical filesystem operation passes through the process-wide `LibraryMutationGate`.
Git and attachment coordinators share the same `LibraryAccess` contract instead of introducing
independent locks. Room remains disposable: no note content can be recovered from it.

The only network permission is `INTERNET` for configured Git HTTPS sync. There is no camera or
broad storage/media permission.

## Git sync

- Manual **Sync Now** is available after configuring an HTTPS repository URL, authentication
  username + PAT, and separate required commit author name/email fields matching the macOS
  settings model. The author fields feed JGit `PersonIdent`; they are not HTTPS credentials.
  The optional automatic setting schedules unique WorkManager sync with a
  15-minute minimum interval and a deduplicated, bounded best-effort run when the process enters
  the background; neither blocks Activity backgrounding or process exit.
- Only local `main` and exact `origin/main` fetch/push refspecs are used. Tags, SSH, URL-embedded
  credentials, redirects, alternate transports, and changed destinations are rejected. TLS
  verification stays enabled and push uses a lease.
- Credentials are encrypted with a non-exportable Android Keystore AES-256-GCM key. The ciphertext
  is stored under `noBackupFilesDir`, bound to the normalized complete repository URL, and is not
  reused after that URL changes. At transport time the provider additionally answers only for the
  exact configured HTTPS host and effective port.
- Syncable files are notes, root `.gitignore`, and content below any `i/` or `files/` attachment
  directory. Trash, hidden paths, symlinks, submodules, non-regular entries, case collisions, and
  unsupported paths stop sync. The 25 MiB ceiling applies independently to each added or modified
  attachment, not notes, deletions, or total library size.
- Diverged histories never receive a textual/3-way merge or conflict markers. MiaoYan shows the
  local filesystem modification time and remote Git last-change time, then requires a whole-file
  Local/Remote choice for every differing path, with choose-all shortcuts. A recoverable ref guards
  checkout, and the current filesystem repository plus Room/UI are refreshed afterward.

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

Git is implemented with Eclipse JGit `7.7.1.202607240634-r`, licensed under the Eclipse
Distribution License 1.0 (`BSD-3-Clause`). The artifact's EDL and bundled SHA-1 UbcCheck MIT notice
are shipped in `app/src/main/res/raw/jgit_notice.txt` and are readable from Settings. Runtime
dependency versions, including JGit, JavaEWAH, Commons Codec, and SLF4J, are recorded in
`app/gradle.lockfile`.

## Build

```bash
cd MiaoYanAndroid
./gradlew test lint assembleDebug
```

The project pins AGP 9.4.0, Gradle 9.6.0, Kotlin/Compose compiler 2.4.10, Compose BOM
2026.06.00, Room 2.8.4, WorkManager 2.11.2, JGit `7.7.1.202607240634-r`, NDK
29.0.14206865, CMake 3.22.1, `compileSdk 36`, `targetSdk
36`, and `minSdk 35`. The prototype deliberately stays on the newest SDK platform currently
available from the installed stable Android SDK channel.

cmark-gfm 0.29.0.gfm.13 is vendored from GitHub at commit `587a12bb54d95ac37241377e6ddc93ea0e45439b`; its source archive checksum and update procedure are recorded in `app/src/main/cpp/third_party/cmark-gfm/README.miaoyan.md`. Builds do not fetch native source from the network.

Prettier 3.9.6 `standalone.js` and its Markdown plugin are vendored from the
official npm package. Version, integrity, hashes, MIT license, third-party
notices, and the update procedure are recorded in
`app/src/main/assets/prettier/README.miaoyan.md`. Its formatting WebView enables
JavaScript only for this bundled page and disables network loads, file/content
access, storage, and new windows. No JavaScript interface is exposed.

The renderer enables the table, strikethrough, autolink, tagfilter, and task-list extensions. It does not pass `CMARK_OPT_UNSAFE`: raw HTML (including iframes) is omitted by cmark before reaching WebView. The single post-render content-policy boundary maps valid local images to the synthetic origin and changes remote images to explicit external links. CSP and `blockNetworkLoads` independently prevent automatic remote media loading.

Presentation uses the same native cmark-gfm content-policy pipeline for every slide and the current editor font/size settings. Reveal.js 4.3.1 core assets are copied from the pinned macOS bundle and run under a nonce CSP; WebView network, file, and content access stay disabled. Only bundled Reveal assets and images accepted by the shared app-private `LocalImagePolicy`/`LocalFileImageLoader` are served through the synthetic appassets origin. The current mode and slide are saveable across rotation. System bars are hidden with `WindowInsetsController` outside multi-window and restored on Back.

See `PRESENTATION.md` for the subsystem boundaries and narrow integration points.

## Deliberate prototype limits

- Import/Export can leave already copied, non-conflicting files when a provider fails partway
  through. Existing canonical files are never overwritten.
- Full crash journaling is not included. Android AI is explicitly out of scope; Git conflicts are
  whole-file user choices only.
- Native APKs are currently produced for `arm64-v8a` devices and `x86_64` emulators only.
