# MiaoYan for Linux on Tauri: product and engineering research

Status: proposed architecture, not an implementation<br>
Research snapshot: **2026-09-07**<br>
Repository baseline: MiaoYan **4.2.0**, commit `668e367e4937bd5c887562991400636ced1d3e58`<br>
Scope: a separate Linux desktop application; **not** KMP and **not** a port of `MiaoYanMobile`

## 0. Executive decision

Build a separate Linux application with **Tauri 2.11.x + Rust + Svelte 5/Vite/TypeScript + CodeMirror 6**, using the host's **WebKitGTK 4.1**. Rust owns every privileged or durable operation: library discovery, file identity, safe writes, watchers, history, Trash, the derived SQLite index, rendering preparation, Git, secrets, AI HTTP, diagnostics, and updates. The frontend owns presentation, ephemeral interaction state, CodeMirror, preview DOM, Reveal.js, and dialogs built from typed Rust events.

This is the smallest stack that can look like MiaoYan rather than a generic IDE while remaining a legitimate Linux application. Use the current **opaque** MiaoYan design and repository SVG assets as the visual source of truth. Do not copy SF Symbols, do not introduce translucent sidebars, and do not revisit the rejected Liquid Glass redesign.

The product target is behavioral parity with macOS except iCloud. “Parity” does not mean reproducing unsafe platform accidents. Linux deliberately changes four semantics:

1. raw HTML and local resources render behind a sanitizer, CSP and an opaque custom protocol rather than a WebView with read access to `/`;
2. pins/cursors have a portable state-database fallback because xattrs are not reliable on every Linux filesystem;
3. filesystem writes use a portable compare-and-swap contract rather than macOS `renamex_np(RENAME_SWAP)`;
4. Git sync, conflicts and optional AI resolution are new Linux product surfaces, not current macOS behavior.

Two release gates must be resolved before promising production parity:

- **Git security gate:** as of the snapshot, `git2` 0.21.0 binds libgit2 1.9.x, while libgit2 1.9.4 is still affected by published, unpatched 2026 advisories. The sync worker may be developed behind a feature flag, but shipping it requires either a patched pinned libgit2, a successful gix re-evaluation, or a reviewed system-Git helper fallback.
- **PDF fidelity gate:** WebKitGTK exposes `WebKitPrintOperation`, but Tauri has no stable cross-platform high-level “export this webview to PDF with our outline” abstraction. A Linux-only bridge and golden PDF tests must pass before PDF can leave beta.

Estimated effort for one production-grade x86_64 release: **30–40 engineer-weeks plus 8–12 QA/design-weeks**, or roughly **6–8 calendar months with two engineers and shared QA/design**. ARM64 and Flathub add 3–5 engineer-weeks after the first stable release.

## 1. Scope and non-goals

### Required product scope

- filesystem library with nested folders, symlink handling, watching, autosave, version history, recoverable Trash;
- Markdown editor with robust CJK IME, syntax highlighting, typewriter/scroll behavior and Prettier typesetting;
- cmark-gfm/GFM, frontmatter, wikilinks, backlinks, search, pins;
- local `i/` and `files/` attachments;
- explicit safe policy for raw HTML, iframe, image, audio and video;
- Mermaid, PlantUML and math;
- continuous fullscreen preview and Reveal.js slides;
- HTML/PDF export with asset and diagram readiness;
- Git HTTPS sync using `origin`/`main`, username + PAT, 25 MiB per-file product limit, manual/15-minute/best-effort-exit sync;
- local/remote conflict UI and optional OpenAI-compatible AI resolver;
- local diagnostics;
- deb, AppImage and later Flatpak packaging, plus channel-correct updates.

### Non-goals

- no iCloud compatibility layer;
- no Swift/AppKit embedding and no reuse of the iOS target;
- no mobile build, KMP, Electron, server-backed account system, collaborative editing, Git LFS or SSH Git in v1;
- no pixel-identical macOS window chrome on Linux;
- no arbitrary shell, plugin marketplace, preview browser, or broad filesystem bridge exposed to JavaScript;
- no upstream changes in this research task.

## 2. What the current macOS product actually does

The implementation baseline was derived from `AGENTS.md`, `ARCHITECTURE.md` and the narrow ownership files below. The filesystem is authoritative; there is currently no first-party Git or OpenAI synchronization layer. The app delegates cross-device sync to iCloud or a user-selected third-party folder.

| Concern | macOS source of truth | Observed contract to preserve |
|---|---|---|
| process/UI ownership | `Controllers/MainWindowController.swift`, `Controllers/ViewController.swift` | one main document window/controller, AppKit split panes, one preview `WKWebView` per editor pane |
| responsive three-pane layout | `Controllers/ViewController+Layout.swift` | sidebar 138–280 px, default note list 280 px, pane-collapse states, editor-only titlebar spacing |
| colors/selection/toolbar | `Helpers/Theme.swift`, asset catalogs | opaque light/dark surfaces, 8 px selection radius, existing accent and icon treatment |
| editor buffer identity | `Views/EditTextView.swift` | selected note and buffer owner can diverge; wholesale publish and persist are guarded operations |
| notes/storage/Trash | `Business/Storage.swift`, `Business/Note.swift` | `.md`/`.markdown`/`.txt`, nested folders, `i/` and `files/`, symlinks, app/system Trash, fail-closed writes |
| filesystem conflicts | `Helpers/FileSystemEventManager.swift` | do not overwrite an active dirty buffer; retain conflict copies under `.miaoyan-conflicts` |
| history | `Business/NoteVersionManager.swift` | maximum 20 versions, minimum 300 s interval, skip whitespace-equivalent duplicates |
| Markdown funnel | `Business/Markdown.swift` | cmark-gfm, footnotes + table/strikethrough/tasklist extensions, math protection, GitHub Alerts post-transform |
| preview shell | `Business/HtmlManager.swift`, `Views/MPreviewView.swift`, `DownView.bundle/css/*` | common live/export/PPT typography and light/dark themes; local scripts and generated HTML |
| editor formatting | `Controllers/ViewController+Editor.swift`, `Helpers/TypographyCleaner.swift` | Prettier Markdown with `proseWrap: preserve`, `htmlWhitespaceSensitivity: ignore`; protected typography regions |
| export | `Extensions/MPreviewView+Export.swift`, `Helpers/PdfExportController.swift` | await images/diagrams, continuous or paginated PDF, outline/metadata/footer behavior |
| wikilinks | `Business/WikilinkIndex.swift` | `[[note]]` index/backlinks follow note loading, recursive search and Trash exclusions |
| diagnostics | `Controllers/AppDelegate.swift`, `Helpers/Diagnostics.swift` | one error funnel; release `.fault` log plus a 50-entry JSONL ring, no analytics SDK |

### 2.1 Visual baseline

These values become versioned design tokens, not approximate developer preferences:

| Token | Light | Dark | Notes |
|---|---:|---:|---|
| app/editor background | `#FFFFFF` | `#23282D` | opaque; no glass/material |
| standard divider | `#E6E6E6` | `#343B43` | one physical pixel at the active scale |
| list selection | `#D9D9D9` family | `#343B43` family | exact active/inactive variants copied from `Theme.swift` |
| accent | `#1C5D33` family | `#54C59F` family | preserve current selected/icon behavior |
| preview text | `#262626` | `#E7E9EA` | from `typography.css` / `theme-dark.css` |
| preview link | `#0C6ADA` | `#1D9BF0` | explicit dark override is required |
| title syntax | current purple | `#A178FF` family | derive exact values from source assets at implementation time |
| editor link syntax | current teal | `#61FFC9` family | do not substitute toolkit defaults |

Dimensions and typography:

- sidebar: default/minimum 138 px, maximum 280 px; outline row 50 px, 14 px indentation, 14 px label;
- note list: default 280 px, minimum 220 px, row 48 px, 1 px intercell spacing, 14 px title, 11 px date, 30 px content inset;
- sidebar selected shape: 8 px radius, 6 px horizontal and 3 px vertical inset;
- editor: 16 px default, line spacing 3 px, line-height multiplier 1.3, letter spacing 0.5 px, content margin 24 px;
- preview: 16 px default, `#write { padding: 0 28px 80px; max-width: 100%; }`, body line height approximately 1.74;
- presentation: 24 px default;
- title: 20 px; search 12 px;
- titlebar content heights: 52 px normal, 54 px narrow, 66 px editor-only; retain current 16/22/32 px title top and 25/30 px leading rhythm.

All preview `img`, `video`, `iframe`, `table` and other replaced/wide content must retain `max-width: 100%`. The editor/preview must never introduce a document-wide horizontal scrollbar.

### 2.2 Behavioral invariants that matter more than framework choice

1. **Buffer ownership:** a buffer has an immutable `note_id` owner until a successful publish. A save carries `note_id`, `base_revision` and content. The backend rejects mismatches; it never infers a target from list selection.
2. **Existing-note writes fail closed:** if the inode/path identity expected by the open session vanished or changed, autosave creates a recoverable conflict record; it never recreates a deleted note silently.
3. **Delete retires the model first:** only a successful filesystem Trash operation removes the row and retires callbacks/watch events/uploads for that `note_id`.
4. **Filesystem is canonical:** SQLite accelerates queries and stores portable metadata, but deleting the database cannot lose notes.
5. **Frontmatter is stripped by one semantic function** for preview, export, PPT, indexing excerpts and AI payload assembly. CRLF and LF delimiters have identical behavior.
6. **CJK composition is sacred:** no formatting, decoration rebuild or full document replacement while `compositionstart`…`compositionend` is active.
7. **Async stays async:** scanning, note reads, hashing, rendering preparation, Git and export do not block the GTK/UI thread.

### 2.3 Complete macOS parity ledger

“All features except iCloud” is tracked as an explicit ledger, not inferred from the architecture. Phase numbers refer to section 13. A stable parity claim requires every row below to pass its Linux acceptance tests; a feature may adopt Linux conventions without disappearing.

| macOS surface/action | Linux contract | Phase |
|---|---|---:|
| choose/change filesystem library; nested projects | portal-selected root, recursive tree, remembered grant, All/Recent/Trash aggregate views | 1 |
| new note, Search and Create, new folder, import files | same outcomes and collision rules; native portal for imports | 1–2 |
| rename, move, duplicate, reload, delete/recover | owner-safe transactions; duplicate associated `i/`/`files/` assets; recoverable Trash only | 1–2 |
| global and per-project sorting | creation/modification/title, ascending/descending; persist locally | 2 |
| pin/unpin | same list priority with xattr plus portable state fallback | 2 |
| search/autocomplete/wikilinks/backlinks | recursive, Trash-aware, rank-compatible, keyboard navigable | 2 |
| note info | word count, creation/modification dates and backlinks from committed revision | 2 |
| plain Markdown editor | syntax emphasis, task-checkbox interaction, undo/redo, selection, clipboard and IME-safe editing | 1–2 |
| find/replace | scoped editor commands with Linux `Ctrl` accelerators and composition-safe replacements | 2 |
| format and Clean Typography | pinned Prettier options and protected-region parser | 2 |
| paste/drop attachments | local `i/`/`files/` convention, safe names, placeholders tied to note/revision/generation | 2 |
| PicGo/PicList upload | optional loopback-only `127.0.0.1:36677` Rust request, validated response, local fallback | 2 |
| clean unused attachments | preview exact candidates, move to Trash, never direct deletion | 2 |
| editor, split preview, preview-only | identical pane-state model, typography, themes and no horizontal overflow | 1–3 |
| bidirectional editor/preview scroll | mapping tied to render revision; throttled and disabled when mapping is stale | 3 |
| TOC and zoom/Actual Size | focused-surface actions; zoom state separated for editor/preview/presentation | 3 |
| continuous fullscreen preview | compositor fullscreen, continuous content and restored prior pane/scroll state | 3 |
| Reveal.js presentation and Magic PPT | current `---` slide semantics, local vendored assets, focused-window controls | 3 |
| export image, HTML, PDF and MiaoYan PPT | revision-pinned jobs; await fonts/images/diagrams; portal PDF fallback behind stated gate | 3 |
| copy note/plain text/HTML/title/path/MiaoYan URL | clipboard formats preserved; Linux deep link is versioned and root-confined | 2–3 |
| reveal in Finder / open project in Terminal | reveal in default file manager; explicit argv launch of a supported/user-selected terminal, never a generic shell command | 2 |
| version history | 20 snapshots, 300 s normal interval, pre-destructive snapshot and non-destructive restore | 1 |
| preferences | editor mode, library, themes, fonts/sizes/margins, line-break mode, always-on-top and upload provider | 1–3 |
| menus, shortcuts, titlebar, focus | complete action discoverability with Linux labels/decorations and remappable `Ctrl` shortcuts | 1–5 |
| single-file/folder open mode | portal/CLI open enters a visibly scoped session without silently replacing the saved library | 2 |
| `miao` CLI (`open/new/search/list/cat/update`) | separate Linux CLI using XDG state and a versioned local app activation/RPC contract | 2 |
| diagnostics, About and update check | local redacted support bundle; package-channel-aware signed update behavior | 5 |
| iCloud | intentionally absent | — |
| Git sync/conflicts/AI resolver | new Linux-only scope specified in sections 7–8, not claimed as macOS parity | 4 |

The current macOS editor disables spellchecking; Linux keeps it off by default for semantic parity. Enabling WebKit/desktop spellcheck is a separate product option because it affects CJK input, context menus and dictionaries.

## 3. Technology snapshot and decisions (2026-09-07)

Versions below are the latest stable versions observed on the research date, not unconstrained dependency declarations. Production uses an exact lockfile and a quarterly upgrade/security review.

| Component | Snapshot | Decision and authoritative source |
|---|---:|---|
| Tauri | 2.11.5 | use; [Rust API](https://docs.rs/tauri/latest/tauri/struct.Builder.html), [architecture](https://v2.tauri.app/concept/architecture/) |
| WRY | 0.56.1 | inherited through Tauri; [WRY docs](https://docs.rs/wry/latest/wry/) |
| Linux web engine | WebKitGTK 4.1 ABI | system dependency; [Tauri webview versions](https://tauri.app/reference/webview-versions/), [prerequisites](https://v2.tauri.app/start/prerequisites/) |
| Svelte | 5.57.0 | use with Vite and TypeScript, no SSR/runtime server; [releases](https://github.com/sveltejs/svelte/releases) |
| CodeMirror | view 6.43.11, state 6.7.4, language 6.12.4, Markdown 6.5.2, search 6.7.2 | use after IME spike; [guide](https://codemirror.net/docs/guide/), [reference](https://codemirror.net/docs/ref/) |
| Monaco | 0.56.0 | reject for v1, retain benchmark fallback; [releases](https://github.com/microsoft/monaco-editor/releases) |
| cmark-gfm | 0.29.0.gfm.13 | compile pinned C library and expose a narrow safe Rust wrapper; [releases](https://github.com/github/cmark-gfm/releases) |
| Prettier | 3.9.6 | vendor standalone + Markdown/estree plugins in a Web Worker; [releases](https://github.com/prettier/prettier/releases), [browser API](https://prettier.io/docs/browser/) |
| Mermaid | 11.17.2 | vendor, `securityLevel: "strict"`; [configuration](https://mermaid.js.org/config/usage.html) |
| KaTeX | 0.18.7 | vendor, `trust: false`; [releases](https://github.com/KaTeX/KaTeX/releases), [security options](https://katex.org/docs/options) |
| Reveal.js | pin current stable during implementation | vendor and lock after compatibility/golden-slide pass; [releases](https://github.com/hakimel/reveal.js/releases) |
| notify | 8.2.0 | use with inotify + fallback polling; [docs and caveats](https://docs.rs/notify/latest/notify/) |
| notify-debouncer-full | 0.7.0 | use for rename stitching/coalescing; [docs](https://docs.rs/notify-debouncer-full/latest/notify_debouncer_full/) |
| rusqlite | 0.40.2 | use with bundled SQLite/FTS5; [docs](https://docs.rs/rusqlite/latest/rusqlite/) |
| SQLite FTS5 | bundled version selected by lockfile | derived index only; [FTS5](https://sqlite.org/fts5.html), [WAL](https://sqlite.org/wal.html) |
| git2 | 0.21.0 | conditional choice behind security gate; [docs](https://docs.rs/git2/latest/git2/) |
| libgit2 | 1.9.4 | do not ship network sync while known advisories lack a patched release; [releases](https://github.com/libgit2/libgit2/releases) |
| gix | 0.87.1 | re-evaluate, not first choice today; [docs/trust model](https://docs.rs/gix/latest/gix/) |
| secret-service | 5.2.0 | use Linux Secret Service with encrypted DH session; [crate](https://docs.rs/secret-service/latest/secret_service/), [spec 0.2](https://specifications.freedesktop.org/secret-service/latest/) |
| libsecret | 0.21.3 | reference C client, not linked by default; [official API](https://gnome.pages.gitlab.gnome.org/libsecret/) |
| ashpd | 0.13.13 | use for XDG portals where Tauri/GTK does not already do so; [docs](https://docs.rs/ashpd/latest/ashpd/) |

### 3.1 Why Tauri, and what it does not solve

Tauri gives a Rust application process, a small bundled frontend, system WebView integration and capability-scoped IPC. It does not make browser content trusted, does not sandbox Rust, does not make WebKitGTK versions uniform across distributions and does not turn HTML controls into native GTK widgets. Tauri's own [security model](https://v2.tauri.app/security/) explicitly leaves the Rust core and command scopes inside the trusted computing base.

Consequences:

- ship no remote frontend code or CDN resources;
- keep command count small and typed; never expose generic `readFile`, `writeFile`, `fetch`, `shell`, `sql` or path commands to JavaScript;
- define a restrictive [application manifest and capabilities](https://v2.tauri.app/security/capabilities/), not the permissive default command set;
- test against the oldest and newest supported WebKitGTK, not merely the CI image;
- use native dialogs/portals and server-side window decorations, while rendering the three content panes in the web frontend.

### 3.2 Frontend decision: Svelte, not React/Vue/vanilla

Svelte's compiled components and small state surface fit a single-window editor. Vite supplies deterministic static assets; TypeScript and generated DTOs constrain IPC. React is viable but adds no product advantage here. Vue is similarly viable. Vanilla DOM would minimize dependencies but makes the conflict/history/settings surfaces harder to keep coherent and accessible. SvelteKit/SSR is specifically unnecessary: there is no web server, route hydration or public site.

### 3.3 Editor decision: CodeMirror 6 over Monaco

| Criterion | CodeMirror 6 | Monaco 0.56 | Decision |
|---|---|---|---|
| product fit | composable text editor toolkit | full IDE editor | CodeMirror |
| visual control | small DOM/CSS surface, easy MiaoYan typography | IDE gutters/minimap/worker assumptions must be disabled/restyled | CodeMirror |
| bundle/runtime | modular packages | larger core and workers, although modern ESM is tree-shakeable | CodeMirror |
| Markdown | first-party Markdown package/GFM configuration | capable tokenizer but not MiaoYan-specific by default | CodeMirror |
| huge files | virtualized viewport, must benchmark | mature large-file behavior | tie until spike |
| CJK/IME on WebKitGTK | contenteditable path needs explicit fcitx5/IBus validation | textarea/EditContext paths also need validation | no theoretical winner |
| accessibility | browser semantics, configurable | strong editor semantics but heavier | validate both |

Go/no-go spike: 100k lines, 1M UTF-16 units, a 64k single paragraph, zh-Hans Pinyin, zh-TW Zhuyin, Japanese Mozc and Korean Hangul on GNOME/Wayland and KDE/Wayland. During composition, incremental decorations may update outside the composing range, but no transaction may replace or reformat composed text. If CodeMirror loses composition, caret position or undo correctness in any P0 case, benchmark Monaco with identical fonts/features before building the editor layer.

### 3.4 Native-looking means Linux-correct, not macOS cosplay

- Keep MiaoYan's opaque pane colors, row geometry, SVG icons and content typography.
- Use normal server-side window decorations and the desktop's close/minimize/maximize ordering. No fake macOS traffic lights.
- Use `Ctrl` for application shortcuts and display the compositor/portal's chosen global shortcut. Preserve the logical macOS commands, not `Cmd` glyphs.
- Use GTK/XDG portal dialogs, desktop notifications, default browser and system Trash.
- Respect system light/dark, text scale, reduced motion, high contrast and locale. A manual MiaoYan appearance override remains possible.
- Use a standard application menu/hamburger appropriate to the desktop; do not rely on a macOS global menu.
- Window position and focus requests are best effort. Under Wayland the compositor is authoritative; restore size/pane widths, not absolute coordinates.
- Fullscreen preview uses the compositor fullscreen API. “Always on top” is offered only if the backend supports it and is labeled best effort.
- Tray is optional and off by default. A StatusNotifier/AppIndicator host may not exist, so sync never depends on a tray icon.

## 4. Proposed architecture

```text
┌──────────────────────────── Tauri main process ─────────────────────────────┐
│ Rust application core                                                       │
│  LibraryService ─ NoteStore ─ WatchCoordinator ─ HistoryStore               │
│        │              │             │               │                       │
│        └──────── IndexService (SQLite/FTS5, rebuildable) ─ WikilinkGraph     │
│                       │                                                     │
│  RenderService ─ AssetRegistry ─ ExportService                              │
│                       │                                                     │
│  SyncScheduler ─ isolated GitWorker ─ ConflictStore ─ optional AiResolver   │
│                       │                      │              │                │
│  SecretService ─ PortalService ─ Diagnostics ─ UpdateService                │
│                                                                            │
│ Narrow commands/events; validated IDs, revisions and opaque asset handles   │
├────────────────────────────── IPC boundary ─────────────────────────────────┤
│ Svelte shell                                                                │
│  Sidebar │ Note list │ CodeMirror editor │ sanitized Preview/Reveal iframe  │
│  Search  │ History   │ Conflict UI       │ Settings/diagnostics             │
└────────────────────────────────────────────────────────────────────────────┘
```

### 4.1 Rust crates/modules

```text
src-tauri/src/
  app.rs                   lifecycle and state wiring
  commands.rs              the only frontend command surface
  model/                   IDs, revisions, DTOs, errors
  library/scan.rs          recursive scan, symlink graph, Trash exclusions
  library/note_store.rs    open/save/create/move/retire contracts
  library/attachments.rs   i/files validation and imports
  library/watch.rs         notify debounce, polling fallback, reconcile
  history/store.rs         snapshots, retention and restore
  index/db.rs              schema/migrations/FTS rebuild
  index/search.rs          exact macOS query semantics and ranking
  index/wikilinks.rs       links, backlinks and invalidation
  render/markdown.rs       cmark-gfm wrapper and frontmatter
  render/sanitize.rs       raw HTML/media policy
  render/assets.rs         opaque custom-protocol registry
  export/mod.rs            HTML/PDF/PPT readiness and jobs
  sync/scheduler.rs        manual/15m/exit orchestration
  sync/git_worker.rs       subprocess boundary and typed protocol
  sync/conflicts.rs        three-way classification and durable records
  ai/resolver.rs           opt-in provider client, schema validation
  platform/portal.rs       picker/open/print/trash/shortcuts
  platform/secrets.rs      Secret Service only, no plaintext fallback
  diagnostics.rs           redaction, ring buffer and support bundle
  updater.rs               direct AppImage channel only
```

The final folder names may differ, but these ownership boundaries must not collapse into Tauri commands or Svelte stores.

### 4.2 Runtime concurrency

- GTK/Tauri main thread: window, menus, dialogs and final UI event delivery only.
- one serialized `NoteStore` actor/task owns mutations and revisions;
- one SQLite writer connection in WAL mode; bounded read pool; database stored under XDG state, never inside the library;
- bounded CPU pool for parsing, hashing and export preparation;
- watchers enqueue normalized hints; a reconciler stats/hashes authoritative paths before changing model state;
- Git runs in a separate helper process so parser/network failures do not corrupt UI state or retain PATs in the WebView process;
- every long job has an operation ID, cancellation and monotonic progress.

### 4.3 IPC allowlist

Frontend capabilities should expose semantic commands only:

```text
library.choose_root, library.open, library.rescan
note.open, note.create, note.save, note.move, note.trash
attachment.import, attachment.trash
search.query, links.backlinks
history.list, history.preview, history.restore
render.document, export.start, export.cancel
sync.configure, sync.start, sync.status, conflict.resolve
ai.configure, ai.test, ai.propose_resolution
settings.read, settings.update
diagnostics.summary, diagnostics.export
update.check, update.install
```

No command accepts an arbitrary destination path after library selection. Mutations accept opaque IDs allocated by Rust and validated against the active library root. Events are namespaced and versioned (`note.changed.v1`, `watch.conflict.v1`, `job.progress.v1`).

## 5. Durable model and data contracts

### 5.1 Identity and revisions

```ts
type NoteId = string;       // random opaque ID, never a frontend path
type LibraryId = string;
type Revision = string;     // BLAKE3(exact content bytes); identity is checked separately

interface NoteDescriptorV1 {
  schema: 1;
  libraryId: LibraryId;
  noteId: NoteId;
  relativePathDisplay: string;
  title: string;
  extension: "md" | "markdown" | "txt";
  revision: Revision;
  modifiedUnixMs: number;
  pinned: boolean;
  retired: boolean;
}

interface SaveNoteV1 {
  schema: 1;
  noteId: NoteId;           // explicit buffer owner
  baseRevision: Revision;   // revision loaded into this buffer
  bufferGeneration: number; // monotonic per editor session
  utf8: string;
  reason: "debounce" | "explicit" | "blur" | "window-close" | "exit";
}
```

`note.save` succeeds only if the ID is live, its resolved path remains inside the library, the current identity/revision matches `baseRevision`, and the file still exists for an existing-note save. The success result supplies the new revision. `RevisionMismatch`, `MissingTarget`, `OutsideRoot`, `ReadOnly`, `InvalidUtf8`, and `Io` are distinct typed errors.

### 5.2 Portable atomic write

For a normal local filesystem:

1. open parent directory without following an unvalidated final symlink;
2. verify target identity/revision immediately before writing;
3. create a unique sibling with `O_CREAT|O_EXCL`, mode derived from target, never a predictable `/tmp` file;
4. write all bytes, `fsync` the temporary file;
5. rename over the target atomically;
6. `fsync` the parent directory;
7. stat/hash the committed target and publish the new revision;
8. remove temp on failure and record a diagnostic without content.

If the filesystem cannot guarantee the operation, report degraded durability and keep the old file/recoverable draft. Never pretend NFS/SMB has local ext4 semantics. This replaces `RENAME_SWAP`; rollback is provided by pre-write history plus conflict drafts.

### 5.3 Metadata placement

| Data | Location | Authority |
|---|---|---|
| note bytes and attachments | user library | canonical |
| pin and cursor | xattr when supported; mirrored portable SQLite row keyed by canonical library/path identity | SQLite fallback wins only when xattr is unavailable, never writes sidecars into user folders |
| note versions | `$XDG_STATE_HOME/com.tw93.miaoyan-linux/versions/<library-id>/<note-id>/` | application state |
| conflict drafts | `.miaoyan-conflicts/` under library, hidden from normal notes; optional move to XDG state is a product decision | recoverable, user-visible |
| index/cache | `$XDG_STATE_HOME/com.tw93.miaoyan-linux/index.sqlite3` | fully rebuildable |
| settings | `$XDG_CONFIG_HOME/com.tw93.miaoyan-linux/settings.json` | canonical settings |
| logs | `$XDG_STATE_HOME/com.tw93.miaoyan-linux/log/diagnostics.jsonl` | local support data |
| transient sockets/locks | `$XDG_RUNTIME_DIR/com.tw93.miaoyan-linux/` | session-only |

These paths follow [XDG Base Directory 0.8](https://specifications.freedesktop.org/basedir/latest/). Directory permissions are `0700`; secret material never enters any of them.

### 5.4 SQLite schema (derived, versioned)

```sql
CREATE TABLE libraries(
  id TEXT PRIMARY KEY, root_display TEXT NOT NULL, root_fingerprint BLOB NOT NULL,
  scan_generation INTEGER NOT NULL, last_full_scan_ms INTEGER
);
CREATE TABLE notes(
  id TEXT PRIMARY KEY, library_id TEXT NOT NULL, relative_path BLOB NOT NULL,
  path_display TEXT NOT NULL, extension TEXT NOT NULL, title TEXT NOT NULL,
  content TEXT NOT NULL, revision TEXT NOT NULL, mtime_ms INTEGER NOT NULL,
  file_dev INTEGER, file_ino INTEGER, pinned INTEGER NOT NULL DEFAULT 0,
  retired INTEGER NOT NULL DEFAULT 0, UNIQUE(library_id, relative_path)
);
CREATE VIRTUAL TABLE notes_fts USING fts5(
  title, content, content='notes', content_rowid='rowid', tokenize='trigram'
);
CREATE TABLE links(source_note_id TEXT, target_normalized TEXT, raw_label TEXT,
  source_start INTEGER, source_end INTEGER);
CREATE TABLE cursors(note_id TEXT PRIMARY KEY, utf16_offset INTEGER NOT NULL,
  revision TEXT NOT NULL, updated_ms INTEGER NOT NULL);
CREATE TABLE sync_state(library_id TEXT PRIMARY KEY, head_oid TEXT,
  last_success_ms INTEGER, next_due_ms INTEGER, last_error_code TEXT);
CREATE TABLE conflicts(id TEXT PRIMARY KEY, note_id TEXT, base_oid TEXT,
  local_revision TEXT, remote_oid TEXT, status TEXT, created_ms INTEGER);
```

Migrations are transactional. On corruption or incompatible schema, move the database to a timestamped diagnostic location and rebuild from the library. `PRAGMA integrity_check` runs after unclean exit and before migration. WAL is appropriate because this database lives locally; [SQLite explicitly warns](https://sqlite.org/wal.html) that WAL does not work over a network filesystem.

### 5.5 Search contract

The current search is not generic FTS relevance. Preserve it exactly:

- lowercase query; split on spaces; all terms must occur in title or content;
- priority 4: all in title; 3: more title than content; 2: mixed with some title; 1: content only;
- order by priority descending, then modified time descending;
- global interactive search returns at most 100 results;
- Trash/project filters apply before ranking;
- prefix-in-title behavior used by autocomplete remains separate.

FTS5 trigram is a candidate generator, not the final semantic judge. Rust re-checks Unicode lowercase substring containment and computes the macOS rank. Queries shorter than three characters, combining marks and CJK edge cases use a bounded scan of indexed strings. Golden tests compare Linux results/order against fixtures produced by the Swift behavior.

### 5.6 Watcher and reconciliation contract

Use `notify` 8.2 + full debouncer, but treat events as hints. Its own documentation notes differing editor save patterns, inotify limits, missed events and network-filesystem gaps. The pipeline is:

```text
raw event → normalize/merge rename → suppress own-write token → stat/hash reconcile
          → update/retire/reload OR create durable conflict → index/link delta → UI event
```

- watch every selected library root recursively and its parent when needed to detect root removal;
- detect symlink loops using `(st_dev, st_ino)` for directories, with canonical-path fallback;
- deduplicate notes reached by multiple symlinks;
- never follow a symlink whose resolved target escapes an explicitly selected library unless the user separately grants that target as a library;
- on inotify exhaustion, surface a persistent degraded-mode banner and switch that library to `PollWatcher`;
- use periodic reconciliation (default 60 s local, configurable 5–15 s for known network mounts) plus focus-triggered rescan;
- an active dirty buffer receiving an external revision produces a conflict; it is never auto-reloaded or overwritten.

## 6. Feature mapping and detailed behavior

### 6.1 Library, projects, notes and Trash

- “Choose Library” uses XDG FileChooser with `directory=true`. Portal grants must be persisted for Flatpak.
- Scan `.md`, `.markdown`, `.txt`; skip dot directories, `.Trash`, `.miaoyan-conflicts`, `.git`, application temp names and attachment-only leaf folders.
- Preserve nested projects and current sidebar aggregate views. Directory symlinks remain supported under the confinement rule above.
- Create/rename/move validates Unicode filename, reserved internal paths, collision after case folding on case-insensitive mounts, and target containment.
- Keep app Trash if present. Otherwise call the [XDG Trash portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Trash.html) or a FreeDesktop Trash implementation. Only remove UI rows on reported success.
- The macOS “removed from Trash” xattr case becomes a portable tombstone row keyed by file identity and operation ID, with xattr as an optimization. Finder/file-manager restoration into a normal project must become visible again.
- Version history keeps at most 20 meaningful snapshots per note, normally no more than one every 300 seconds, plus forced pre-destructive snapshots. Restoring creates a new version; it does not erase later history.

### 6.2 Editor, IME and performance levels

CodeMirror state contains `{noteId, baseRevision, bufferGeneration, dirty}`. Selection changes do not change the owner of an already-published buffer. Switching notes waits for either successful save, explicit “Keep draft”, or conflict UI.

Autosave:

- 1.5 s debounce after edits;
- flush on explicit Save, focus loss, note switch, window close and app shutdown;
- exit flush has a bounded local-write budget (recommended 3 s); Git exit sync is a separate best-effort operation and cannot block data persistence;
- failed save retains dirty state and a recoverable XDG-state draft, with a visible error.

Performance modes mirror macOS thresholds initially:

| Condition | Editor behavior |
|---|---|
| >1,000,000 UTF-16 units or >64,000-unit single paragraph | simplified Markdown decorations, code highlighting off |
| >5,000 lines | simplified highlighting, code highlighting off |
| >2,000 lines | simplified highlighting, bounded code highlighting |
| below thresholds | full Markdown decoration/highlighting |

Preview debounce begins at 300 ms under 500 lines, 600 ms under 1,500, 1,000 ms above; split-scroll synchronization is throttled around 150 ms. Thresholds are hypotheses until Linux benchmarks, not sacred constants.

CJK acceptance criteria:

- composition text is never duplicated, committed early, moved, rewrapped or sent to Prettier;
- undo removes one IME commit as the input method expects;
- caret and candidate popup remain attached through scroll/resize/fullscreen;
- `line-break: normal`, `overflow-wrap: break-word`, no CSS `word-break: break-all`; CodeMirror line wrapping is tested at punctuation boundaries;
- zh-Hans/zh-Hant/ja/ko clipboard, filename, search and Markdown offsets round-trip without converting byte offsets to UTF-16 incorrectly.

### 6.3 Prettier and Clean Typography

Run pinned Prettier standalone offline in a dedicated Worker with the Markdown and estree plugins. Preserve the current options from [Prettier's documented contracts](https://prettier.io/docs/options):

```json
{
  "parser": "markdown",
  "proseWrap": "preserve",
  "htmlWhitespaceSensitivity": "ignore"
}
```

Formatting is an explicit command, cancellable, applied as a single undo transaction only if `noteId`, `baseRevision` and `bufferGeneration` are unchanged when the worker returns. Maintain the current raw-HTML placeholder compatibility and cursor mapping as golden fixtures.

Clean Typography shares one parser/segmenter with macOS semantics. It may transform prose but never frontmatter, fenced/inline code, math, link targets, wikilinks or bare URLs. It cannot run during IME composition.

Attachment insertion preserves the current `/i/<name>` image convention and sibling `files/` convention. Imports validate decoded MIME/signature, allocate a collision-safe filename in Rust, write atomically, then insert Markdown only if the originating `{noteId, baseRevision, bufferGeneration}` is still current. A late upload/import callback cannot target the newly selected note. “Clean Unused Attachments” is a revision-pinned, preview-before-action operation and moves candidates to Trash.

The existing PicGo/PicList integration is parity scope, not a generic network bridge. Rust alone may POST to the fixed loopback endpoint `http://127.0.0.1:36677/upload`; it applies body/time/response limits, accepts no redirects away from loopback, validates the returned URL scheme/length, and returns a typed result. The frontend never gets arbitrary fetch capability. On unavailable or malformed service response, preserve the macOS behavior of offering/falling back to a local attachment rather than losing the paste. Whether remote URLs are allowed in preview remains governed by the independent media policy in section 6.5.

### 6.4 Markdown, frontmatter and wikilinks

Compile the upstream cmark-gfm C library as a pinned Rust build dependency and keep its API behind `RenderService`; do not replace it with a merely CommonMark-compatible Rust parser if byte-for-byte parity is required. Enable footnotes and the current `table`, `footnotes`, `strikethrough`, `tasklist` extensions; retain the current GitHub-line-break mode, math protection and GitHub Alerts transform.

Create one Rust `strip_frontmatter_v1(bytes)` implementation and test it for LF, CRLF, BOM, missing close marker, empty body and `---` later in the document. Every output surface calls it. Search may index frontmatter only if the product explicitly decides that; recommended default is **do not index metadata as visible prose**.

Wikilink extraction returns UTF-8 byte and UTF-16 editor ranges, normalized target, display label and source revision. Rebuild links incrementally after successful note commits; exclude Trash/conflict paths. Renames display a preview of affected links and update them as one recoverable batch.

### 6.5 Preview isolation, raw HTML and media policy

The current macOS renderer uses `CMARK_OPT_UNSAFE`, inserts rendered HTML with `innerHTML`, and loads a file URL with read access to `/`. Repeating that behavior would let a note become a privileged local-content gadget. Linux instead preserves supported visual output through a stricter trust boundary.

Render pipeline:

```text
UTF-8 note
  → strip_frontmatter_v1
  → protect math / parse cmark-gfm
  → GitHub Alerts and feature transforms
  → parse HTML (not regex)
  → sanitizer/profile policy
  → rewrite local resources to opaque asset URLs
  → RenderDocumentV1 + CSP
  → preview iframe with no Tauri IPC
```

The application shell and preview are separate origins/contexts. Load preview output into an outer iframe sandboxed with `allow-scripts` only—no `allow-same-origin`, forms, popups, top navigation or downloads. The preview has no Tauri globals and no commands; scroll/TOC/readiness messages cross a schema-validated `postMessage` bridge that verifies `renderId`. The frontend cannot convert an OS path into an asset URL. If the pinned WebKitGTK/Tauri build exposes privileged globals to that frame or cannot enforce the sandbox while running vendored Reveal/Mermaid, Phase 0 must replace it with a dedicated unprivileged WebView whose Tauri capability set is empty. Rust registers a short-lived capability:

```ts
interface RenderDocumentV1 {
  schema: 1;
  renderId: string;
  noteId: string;
  revision: string;
  sanitizedHtml: string;
  headings: { id: string; level: number; text: string; sourceStart: number }[];
  warnings: { code: string; sourceStart?: number; detail: string }[];
  readyAssets: string[];
}

// Example only; IDs are random and expire when renderId is superseded.
// miao-asset://localhost/<render-id>/<asset-id>
```

The custom handler resolves `asset-id` through its in-memory registry, opens without following an unexpected final symlink, rechecks canonical containment and returns a fixed content type, `nosniff`, no-store cache headers and a size-limited stream. On Linux, a custom scheme uses the `scheme://localhost/...` form; see Tauri's [asset-protocol security guidance](https://v2.tauri.app/security/asset-protocol/) and WebKitGTK's [custom URI scheme API](https://webkitgtk.org/reference/webkit2gtk/stable/class.WebContext.html).

Default CSP (exact syntax verified in the implementation WebKitGTK matrix):

```text
default-src 'none';
script-src 'self';
style-src 'self' 'unsafe-inline';
font-src 'self' miao-asset:;
img-src 'self' miao-asset: data:;
media-src miao-asset:;
frame-src 'none';
connect-src 'none';
object-src 'none';
base-uri 'none';
form-action 'none';
```

Tauri does not enable a CSP unless configured, so this is a release test, not documentation intent; see [Tauri CSP](https://v2.tauri.app/security/csp/). Avoid `'unsafe-inline'` for scripts entirely. Styles may use a nonce instead if WebKitGTK confirms dynamic diagram styles work, allowing removal of the style exception.

Content profiles:

| Content | Default | Optional trusted-library behavior |
|---|---|---|
| Markdown-generated HTML | allowed after sanitizer | same |
| raw formatting tags | allow conservative set: headings, paragraphs, lists, tables, details, inline text | optionally widen harmless tags |
| `script`, event attributes, forms, `object`, `embed`, SVG scripts/foreignObject | remove | never allow |
| local images/audio/video | only registered files under library and size/MIME limits | same |
| `data:` | small raster images only; reject SVG/HTML/media | same |
| remote images/media | blocked with click-to-load placeholder | per-library host allowlist; HTTPS only |
| iframe | blocked | per-library exact HTTPS-origin allowlist, click-to-load, sandbox without `allow-same-origin` unless proven necessary |
| arbitrary fetch/WebSocket | blocked | never from preview; backend feature-specific requests only |
| navigation/popups | intercept and ask OS opener | same |

“Trusted library” is not “disable security.” It is a named policy with a visible summary and reset action. Sanitizer tests cover mixed-case attributes, character references, CSS URLs, SVG/MathML namespace confusion, `srcdoc`, protocol-relative URLs, redirects and malicious MIME labels.

### 6.6 Mermaid, PlantUML and math

- Vendor all JavaScript/CSS/fonts; no CDN at runtime.
- Mermaid 11.17.2 uses `startOnLoad: false`, deterministic IDs and `securityLevel: "strict"`, whose documented behavior encodes HTML labels and disables clicks. Sanitize generated SVG again and never call returned event binders.
- KaTeX 0.18.7 uses `trust: false`, bounded macro expansion and error rendering; the documented default blocks commands such as `\includegraphics` that can load external content.
- PlantUML is the only diagram path that requires networking. Default: show a “Render with PlantUML server” affordance and remember consent per library/server. Permit the official server or an exact user-configured HTTPS endpoint; permit loopback HTTP for a local server. No arbitrary redirect, no cookies/auth inherited from the app, response cap 10 MiB, timeout 20 s, SVG sanitizer, cache keyed by source + server origin. Offline failure leaves source and a retry control.
- Diagram rendering is cancellable and keyed by note revision. Stale results are discarded.

### 6.7 Continuous preview, presentation and export

Continuous fullscreen preview is the same sanitized document in a presentation window state: editor/sidebar hidden, normal compositor fullscreen, scroll position preserved, Escape exits. Do not create an always-on-top frameless overlay.

Reveal.js slides use the same renderer/sanitizer and split on the existing `---` convention after frontmatter handling. Bundle Reveal.js and themes; disable remote plugins. Keyboard and presenter actions are scoped to the focused presentation window and must not collide with desktop shortcuts.

HTML export copies a self-contained sanitized document plus only referenced local assets. It never embeds absolute paths, credentials or app IPC. Remote content remains remote only if the user explicitly enables it in export settings.

Image export captures the full continuous `#write` surface at the pinned revision after the same readiness barrier, tiles when dimensions exceed WebKitGTK/GPU texture limits, and stitches with bounded memory into PNG. It must report an actionable limit instead of returning a cropped or blank image. “MiaoYan PPT” export uses Reveal's print layout and emits one PDF page per slide; it is not a PowerPoint `.pptx` promise. Both paths reuse sanitized export HTML and never capture the privileged application shell.

PDF path:

1. render a dedicated hidden export WebView at the pinned revision;
2. await `document.fonts.ready`, all local image/media poster loads, Mermaid/KaTeX completion and PlantUML terminal states;
3. freeze animations/caret and apply print CSS;
4. call a Linux bridge around WebKitGTK [`WebKitPrintOperation`](https://webkitgtk.org/reference/webkit2gtk/stable/class.PrintOperation.html) using GTK print settings/output URI for PDF, or open the [XDG Print portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Print.html) for user-driven printing;
5. verify the file exists and is a valid PDF before reporting success;
6. add an outline only through a reviewed PDF library, based on measured page geometry—not the current approximate scroll ratio.

If automated PDF output cannot be made stable across baseline WebKitGTK versions, v1 exposes “Print / Save as PDF” through the native portal and labels direct PDF export beta. Bundling Chromium solely for PDF is rejected unless product accepts roughly 150–250 MiB extra runtime and a second browser-security update obligation.

## 7. Git synchronization and conflict model

### 7.1 Product semantics

One library maps to one Git working tree. v1 supports:

- remote name exactly `origin`, branch exactly `main`;
- HTTPS URL only; separate username and PAT fields; PAT never appears in the URL, `.git/config`, command line, frontend state or logs;
- manual Sync, periodic Sync every 15 minutes while the app is running, and best-effort sync on clean exit;
- a strict **25 MiB per regular file** product limit before staging/fetch checkout; no Git LFS;
- one sync at a time per library; local note mutations continue, but commit snapshotting takes the serialized store barrier;
- no destructive reset, force push, auto-rebase or silent conflict-marker insertion into an open note.

The 25 MiB value is intentionally stricter than GitHub's command-line 100 MiB limit; GitHub documents 25 MiB for browser uploads and 100 MiB for command-line Git in [Adding a file to a repository](https://docs.github.com/en/repositories/working-with-files/managing-files/adding-a-file-to-a-repository). It is a MiaoYan resource/safety budget, not a claim about all Git servers.

### 7.2 Library choice: git2/libgit2, with a hard gate

`git2`/libgit2 currently has the more direct mature surface for credential callbacks, certificate checks, remote progress, index/worktree mutations and merge analysis. `gix` is promising pure Rust and has an explicit trust model, but its own 0.87.1 documentation notes gaps relative to `git2`, including absent strict hash verification/object-creation checks in current gitoxide. For this product's first implementation, choose `git2` behind an isolated worker and feature flag.

This is not permission to ship the snapshot version. Current primary advisories include:

- [GHSA-2889-x8f6-mc4x](https://github.com/libgit2/libgit2/security/advisories/GHSA-2889-x8f6-mc4x): credentials may be disclosed across off-site redirects; no patched version was listed on the research date;
- [GHSA-pm24-4jhq-3xvm](https://github.com/libgit2/libgit2/security/advisories/GHSA-pm24-4jhq-3xvm): remotely triggerable heap out-of-bounds read affecting libgit2 through 1.9.4; no patched version was listed.

Release gate:

1. pin and inventory the exact `libgit2-sys`/libgit2 commit;
2. no open critical/high network advisory affecting configured code paths, or a reviewed backport with regression tests;
3. fuzz pack/index/checkout and malicious redirect fixtures;
4. keep the worker process and apply OS resource limits;
5. if the gate fails, compare current gix again, then a system `git` helper with an explicit minimum Git version and credential protocol. Do not silently drop the feature or ship the vulnerable library.

Even after a patched libgit2, set [`RemoteRedirect::None`](https://docs.rs/git2/latest/git2/enum.RemoteRedirect.html), validate normal TLS certificates, and require every configured origin to be an exact normalized `{scheme=https, host, port, path}` without userinfo. Reject host/scheme changes. Credential callbacks answer only for that exact origin and only with `USER_PASS_PLAINTEXT` requested.

### 7.3 Isolated worker protocol

The main process starts a fixed bundled helper by absolute path, not a shell. Protocol: length-prefixed CBOR over inherited pipes, request/response IDs, no arbitrary arguments. Worker environment is cleared except locale, proxy configuration explicitly approved by product, CA paths and a one-use secret channel. Apply `RLIMIT_AS`, `RLIMIT_CPU`, file descriptor limits and a 25 MiB blob/worktree preflight; investigate Landlock/seccomp without making unsupported promises.

```ts
interface SyncRequestV1 {
  schema: 1;
  operationId: string;
  libraryHandle: string;  // inherited/open directory handle, not frontend path
  expectedLocalHead?: string;
  origin: { scheme: "https"; host: string; port: number; repoPath: string };
  username: string;          // separate field, never URL userinfo
  credentialChannel: "inherited-fd"; // one-use PAT bytes, absent from argv/env/CBOR
  branch: "main";
  mode: "manual" | "interval" | "exit";
  maxFileBytes: 26214400;
}

interface SyncResultV1 {
  schema: 1;
  outcome: "up-to-date" | "pushed" | "fast-forwarded" | "merged" |
           "conflicts" | "auth-required" | "offline" | "error" | "cancelled";
  beforeHead?: string;
  afterHead?: string;
  conflicts: string[];
  diagnosticsCode?: string;
}
```

The PAT is copied into locked memory only for the operation and zeroized afterward. Perfect anti-swap guarantees are not claimed. Store PAT and AI keys in Linux Secret Service, using lookup attributes (`application=com.tw93.miaoyan-linux`, `library-id`, `kind`) rather than object paths. Use an encrypted DH Secret Service session. If Secret Service is absent or locked, request unlock or session-only credentials; **never** fall back to plaintext.

Secret Service is the desktop D-Bus contract; `libsecret` 0.21.3 is GNOME's C client for that service, not the store itself. Prefer the pure-Rust `secret-service` 5.2.0 client to avoid an extra GLib/GObject/libsecret ABI dependency while still interoperating with conforming services. Keep a provider trait so a packaging test can switch to `libsecret` if the direct client proves incompatible with a supported desktop. Do not maintain two live stores or use one as a silent plaintext fallback. The choice must be re-tested under GNOME Keyring and KWallet, locked/unlocked sessions, no-service sessions and Flatpak D-Bus policy. The [official libsecret API](https://gnome.pages.gitlab.gnome.org/libsecret/) identifies it as a Secret Service D-Bus client; the [Secret Service 0.2 specification](https://specifications.freedesktop.org/secret-service/latest/) defines collections, lookup attributes, encrypted sessions and prompts.

Git's own documentation lists `git-credential-libsecret` as a secure Linux helper and warns about credential-helper context; see [gitcredentials](https://git-scm.com/docs/gitcredentials.html). GitHub likewise says not to put PATs in command lines and to use least privilege/expiration in [credential security guidance](https://docs.github.com/en/rest/authentication/keeping-your-api-credentials-secure).

### 7.4 Sync algorithm

```text
flush active buffers → wait for NoteStore barrier → size/symlink/special-file preflight
→ snapshot/version changed notes → stage allowlisted library content
→ commit local delta (stable configured author; never PAT identity)
→ fetch origin/main with redirect/TLS/object budgets
→ classify ancestry
   ├─ equal: done
   ├─ local ahead: push with expected remote OID (no force)
   ├─ remote ahead: fast-forward only after dirty-buffer conflict check
   └─ diverged: three-way merge into a temporary index/worktree
       ├─ clean: validate result, atomic apply, commit, push
       └─ conflict: persist ConflictSet, leave user files and HEAD unchanged
```

Stage Markdown and user attachments, but always exclude `.git`, `.Trash`, `.miaoyan-conflicts`, app temp files and XDG state. Symlinks are stored as symlinks only if their link text is safe; never dereference an external target into a commit. Special files, sockets and devices are rejected. Before applying fetched changes, enforce file count, total unpacked budget, per-file 25 MiB budget, path depth/length and containment. Detect case-fold and Unicode-normalization collisions before checkout.

The interval timer begins after launch with jitter, pauses offline, uses exponential backoff and does not require tray/background permission. Best-effort exit sync gets a short explicit budget (recommended 5 s) after local flush; if unfinished, cancel safely and show status next launch. A process cannot guarantee network completion during logout/power loss.

### 7.5 Conflict UI and durable state

A `ConflictSet` records base/local/remote blob IDs and immutable copies outside the live file until resolution. The main view shows a non-modal banner and a conflict queue. Each item has:

- path and conflict kind: modify/modify, delete/modify, add/add, rename collision, binary/attachment, case collision;
- Base / Local / Remote read-only panes, inline diff, and editable Result pane for text;
- actions: Use Local, Use Remote, Edit Result, Keep Both; attachment conflicts also show size/hash/preview;
- “Apply” validates that HEAD and local revision still equal the conflict record, writes atomically, versions the previous local file, commits only resolved items and retries push;
- closing the window preserves the queue; autosave cannot write a conflict result to the wrong note.

Never auto-pick by timestamp. Clock skew makes it unsafe. Delete/modify is never silently interpreted as deletion.

## 8. Optional OpenAI-compatible AI resolver

This feature is off by default and available only inside an already-created text conflict. It is a proposal generator, never an automatic merger.

Settings:

- endpoint base URL, model string, API key, optional organization/project headers, system prompt and per-resolution instruction;
- key in Secret Service; non-secret settings in XDG config;
- HTTPS required, except explicit loopback HTTP for a local compatible server;
- exact-origin redirects disabled; cookies, proxy credentials and app user agent secrets excluded;
- “Test connection” sends no note content;
- user sees provider origin, exact files/character count and a privacy confirmation before first send per provider.

For maximum third-party compatibility, start with `POST <base>/v1/chat/completions` using bearer auth and a JSON-only response contract; negotiate `response_format: {type: "json_schema"}` only when the provider declares support. OpenAI's official API overview states that API keys are secrets and must not be exposed in browser/client-side code, so all calls originate in Rust; it also documents request IDs and additive response fields, which means parsing ignores unknown response properties. Source: [official OpenAI API overview](https://developers.openai.com/api/reference/overview).

No claim is made that an “OpenAI-compatible” provider shares OpenAI's retention or privacy behavior. The UI links to the configured provider's policy and treats the provider as an independent data recipient.

Request content contains only:

```json
{
  "contract": "miaoyan.conflict.resolve.v1",
  "path": "relative/display/path.md",
  "base": "...",
  "local": "...",
  "remote": "...",
  "instruction": "Return a merged document; preserve Markdown and frontmatter.",
  "response": { "merged": "string", "summary": "string", "warnings": ["string"] }
}
```

Safety/quality controls:

- only the selected conflict; no library search, tools, attachments or hidden prompt context;
- hard input/output byte limits, 60 s timeout, cancellation and no retries that can double billing without consent;
- treat note text as untrusted data surrounded by explicit delimiters; note instructions cannot change the response schema;
- validate JSON, UTF-8 and result size; scan for conflict markers and require the result to differ from base/local/remote intentionally;
- show the normal Result diff; user must edit/accept and then press Apply;
- log provider origin, duration, model string, status and request ID, but never prompts, content, response or key;
- deterministic fake-provider fixtures and human-reviewed multilingual merge corpus. AI availability never blocks manual resolution.

## 9. Threat model

### 9.1 Assets and trust boundaries

Assets: user notes/attachments, Git integrity, PAT/API keys, filesystem outside the chosen library, history/conflict recovery, release-update key, and user privacy. Untrusted inputs: note Markdown/raw HTML/SVG, attachment bytes/MIME, filenames/symlinks, watcher events, Git remotes/packs/trees, PlantUML/AI/update HTTP responses, clipboard HTML and frontend code after an XSS.

| Threat | Example | Required control | Verification |
|---|---|---|---|
| preview → privileged IPC | raw HTML calls Tauri command | separate preview origin/context, no capability, narrow app manifest | malicious-note E2E must receive no command object |
| arbitrary local read | `file:///etc/passwd`, symlink in `i/` | no file URL, opaque registry, canonical/openat containment | symlink-swap and traversal suite |
| script/style injection | event handlers, SVG, `srcdoc`, CSS URL | parsed sanitizer, CSP, fixed MIME, Mermaid/KaTeX strict modes | OWASP-style corpus + CSP violation test |
| remote tracking/exfiltration | image URL loads on preview | remote blocked by default; click/per-library origin allowlist | proxy capture shows zero unsolicited requests |
| path escape on writes | `../`, rename race, portal doc path | opaque IDs, directory handles, nofollow/recheck, atomic sibling write | race/property tests |
| save wrong note | selection changes in preview mode | explicit owner/revision/generation | regression for macOS issue #543 |
| resurrection after delete | delayed save/watch/upload callback | retired IDs; existing writes require live target | lifecycle race tests |
| data loss on watcher event | editor save appears remove/create | events as hints, hash reconcile, conflict draft | multi-editor and network-FS tests |
| Git credential theft | redirect to attacker origin | no offsite redirects, exact origin callback, secret worker channel | redirect integration server |
| malicious Git object | oversized pack/path/collision/parser bug | patched dependency gate, isolated worker, object/resource/path budgets | fuzz and adversarial repositories |
| token leakage | URL/log/process args/config | Secret Service, structured redaction, no frontend key | process/log/config inspection |
| AI prompt injection | note asks model to expose data | send only selected triplet, no tools, schema, human acceptance | hostile prompt corpus |
| update compromise | forged AppImage/manifest | HTTPS + mandatory Tauri signature + offline public key | tamper/rollback tests |
| denial of service | huge note/diagram/regex/decompression | size/time/memory/cancellation budgets | benchmark/fuzz matrix |

Security response includes a dependency inventory/SBOM, `cargo audit` plus RustSec/GitHub advisory review, npm audit with manual triage, pinned lockfiles, reproducible CI inputs, sanitizer/parser fuzzing and a documented release key rotation/recovery procedure.

## 10. Linux desktop integration

### 10.1 Portals and filesystem grants

Prefer XDG Desktop Portal even for deb/AppImage, with a GTK fallback only when the portal is unavailable. Portals select a desktop-specific backend, so GNOME, KDE, COSMIC and Xfce receive their own dialog conventions. The [FileChooser v4 interface](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.FileChooser.html) supports directory selection and may persist access through the Documents portal.

Use `ashpd` directly for features not covered correctly by a Tauri plugin. Parent every request with the actual window identifier. Handle missing/older portal methods without crashing:

| Need | Primary | Fallback |
|---|---|---|
| choose library/import/export | FileChooser portal | native GTK dialog for unsandboxed package |
| open external URL/file | OpenURI portal | desktop default opener |
| delete | Trash portal | FreeDesktop Trash implementation; never direct unlink as a fallback |
| print | Print portal or WebKitGTK print dialog | explicit unsupported message, keep HTML export |
| notifications | Notification portal | Tauri notification when backend works |
| global shortcut | GlobalShortcuts portal v2 with user confirmation | focused-window shortcut only |

The [GlobalShortcuts portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.GlobalShortcuts.html) lets the user approve/configure compositor-wide shortcuts. Do not use X11 key grabs as an invisible Wayland fallback. MiaoYan does not require a global shortcut for core operation.

### 10.2 Wayland and X11 behavior

Support native Wayland first and X11 as a real CI/manual target. WRY uses a GTK container on Linux and exposes X11/Wayland through that platform stack; see [WRY platform notes](https://docs.rs/wry/latest/wry/). The windowing backend is runtime-selected; backend-specific behavior must be detected rather than inferred from environment variables alone.

| Behavior | Wayland policy | X11 policy |
|---|---|---|
| initial/restored position | compositor decides; restore size and maximized/fullscreen state only | restore visible bounded position if reliable |
| focus/raise | only from user activation/token; no focus stealing | request, still respect window manager |
| fullscreen | Tauri/GTK compositor request; test GNOME/KWin/wlroots | EWMH request |
| always-on-top | best effort, report unsupported/ignored | best effort EWMH |
| custom titlebar | not used | not used |
| global shortcut | portal | portal preferred, Tauri/plugin fallback only with visible ownership/conflict handling |
| drag/drop | portal/toolkit file transfer semantics; test sandbox document URIs | GTK DnD |
| screen capture/color pick | not needed | not needed |

The Wayland `xdg-shell` protocol positions popups relative to parents and leaves toplevel placement to the compositor; it provides no client-set global toplevel coordinates. Source: [canonical xdg-shell protocol XML](https://gitlab.freedesktop.org/wayland/wayland-protocols/-/blob/main/stable/xdg-shell/xdg-shell.xml). Never make window-position precision an acceptance criterion under Wayland.

### 10.3 Menus, tray, lifecycle and fullscreen

- Use a normal application menu with Linux labels (`File`, `Edit`, `View`, `Format`, `Window`, `Help`) and `Ctrl` accelerators. Keep `Ctrl+1…5` free only after a Linux shortcut audit; macOS storyboard reservations are not automatically portable, but feature actions should remain discoverable and remappable.
- System tray uses Tauri's [tray API](https://v2.tauri.app/learn/system-tray/) and StatusNotifier/AppIndicator where hosted. The [StatusNotifierItem specification](https://specifications.freedesktop.org/status-notifier-item/latest/status-notifier-item.html) requires a host/watcher; some desktops may show no item. Therefore tray is a convenience, not lifecycle ownership.
- Closing the last window exits by default after local flush. “Keep running for sync” is optional, explicit, and available only if the desktop/background policy permits it. A 15-minute sync schedule must not surprise users with an invisible permanent process.
- Fullscreen preserves pane visibility/scroll state and restores it after compositor exit. Test Escape, F11/menu action and compositor shortcut paths.
- Use native context menus for text editing where CodeMirror permits; clipboard actions must match GTK/WebKit conventions. Spellcheck stays disabled by default, matching macOS 4.2.0, unless product explicitly adds it.

### 10.4 File manager, terminal and `miao` CLI

“Reveal in Finder” becomes “Show in File Manager” through the OpenURI portal or desktop opener using a validated item URI. “Open Project in Terminal” is a narrow platform adapter: pass the already-authorized directory as a single argv value to a supported or user-selected terminal executable. It must not accept shell fragments, interpolate filenames into `sh -c`, or expose a general shell Tauri command. Under Flatpak, label the action unavailable unless the selected terminal/host-launch policy is explicitly supported.

Ship a separate thin Linux `miao` executable with parity commands `open`, `new`, `search`, `list`, `cat`, and `update`. It reads only non-secret configuration from XDG config/state, activates the GUI through a versioned single-instance local RPC/desktop-activation contract when mutation or UI is required, and never talks directly to frontend IPC. Read-only `list`/`cat` may use the Rust core in-process after validating the library grant; `new` and `update` go through the running app's NoteStore so they share atomic-save, owner and watcher barriers. Authenticate the local socket by user ownership and `0600` permissions, reject peers with a different UID, version every request, cap payloads, and return stable machine-readable exit codes. The macOS script's `defaults`/`open` behavior is not copied.

### 10.5 Fonts and icons

Do not distribute Apple SF fonts or SF Symbols. Their platform license is not a Linux asset license, and the product direction already rejects an SF-Symbol migration. Reuse only repository-owned/licensed SVG assets after creating a third-party notices inventory; render at 16/20/24 px with pixel-grid review in 1.0, 1.25, 1.5 and 2.0 scale factors.

Recommended font strategy:

- UI/editor default: `system-ui, Inter, "Noto Sans", sans-serif`;
- CJK: system fontconfig first, then `"Noto Sans CJK SC"`, `"Noto Sans CJK TC"`, `"Noto Sans CJK JP"`, `"Noto Sans CJK KR"` fallbacks;
- code: `ui-monospace, "JetBrains Mono", "Noto Sans Mono", "Liberation Mono", monospace`;
- preserve user font selection through fontconfig family names, and visibly fall back when missing;
- bundle Inter only if cross-distro golden tests show system UI fonts cannot achieve the intended metrics. Inter's [SIL OFL 1.1](https://github.com/rsms/inter/blob/master/LICENSE.txt) permits bundling with software if its copyright/license accompanies it;
- avoid bundling the very large Noto CJK collection in the first AppImage. If later subset/bundle is approved, retain OFL notices; Noto CJK identifies its license as [SIL OFL 1.1](https://github.com/notofonts/noto-cjk/blob/main/Sans/README-third_party.md).

Font substitution makes exact glyph metrics impossible across all Linux systems. The parity target is layout rhythm and typographic hierarchy; golden screenshots use a declared CI font set, while manual tests cover distribution defaults.

### 10.6 Accessibility and localization

- semantic landmarks for sidebar/list/editor/preview; logical focus order; screen-reader names for icon-only buttons;
- WebKit/CodeMirror accessibility smoke with Orca screen reader on GNOME;
- keyboard-only navigation and visible focus rings using existing accent colors with contrast checks;
- minimum target size 28 CSS px for compact controls, 36 px where layout permits;
- system text scale 100/125/150/200%; pane minimums must not hide destructive controls;
- respect `prefers-reduced-motion` and high contrast/forced colors when WebKitGTK exposes them;
- v1 UI locales: English and Simplified Chinese at minimum; avoid string concatenation and reserve expansion. Markdown content remains Unicode without normalization on write.

## 11. Packaging, updates and supportability

### 11.1 Supported matrix and build baseline

Initial support:

- x86_64 Ubuntu 22.04/24.04/26.04 GNOME, Debian 12/13 GNOME, Fedora current/current-1 GNOME, KDE Plasma current on Fedora KDE or KDE neon;
- native Wayland and X11/XWayland sessions where the desktop offers them;
- system WebKitGTK 4.1 ABI, with the distribution's security updates;
- glibc baseline built on Ubuntu 22.04 or Debian 12. Tauri's official [AppImage guidance](https://v2.tauri.app/distribute/appimage/) explicitly recommends building on the oldest supported base and names those two baselines.

Do not claim “all Linux distributions.” Arch/openSUSE/COSMIC/wlroots are best-effort until added to the release matrix. ARM64 follows only after native-runner builds, because Tauri's AppImage tooling does not support ordinary cross-compilation for ARM AppImages.

### 11.2 Channels

| Format | Release | Updates | Notes |
|---|---|---|---|
| `.deb` | v1 | apt/repository or manual package replacement; never self-overwrite a package-managed install | Tauri bundler supplies desktop entry/icons and WebKitGTK/GTK deps; [Debian packaging](https://v2.tauri.app/distribute/debian/) |
| AppImage | v1 | Tauri updater with signed AppImage | portable primary direct-download channel; enable media framework only after codec/license review |
| Flatpak/Flathub | post-v1 | Flathub/Flatpak only | sandbox grants, Secret Service D-Bus, Git networking, custom protocol, print/export and background behavior need separate hardening; [Tauri Flathub guide](https://v2.tauri.app/distribute/flatpak/) |
| rpm | later if demand | distro repository/manual | Tauri can bundle it, but doubles package QA |

AppImage size is expected to be 70+ MiB before optional media codecs; Tauri documents this order of magnitude and warns that bundling GStreamer media support increases size and may introduce problematic codec licenses. Define the actual playback codec support only after a dependency/license matrix. Unsupported media shows a clear placeholder and “Open externally”; never broaden CSP to make a codec failure disappear.

### 11.3 Updater and signing

Tauri's updater requires a signature and does not permit disabling verification. On Linux it produces/reuses an AppImage and `.sig`; see [Updater signing and Linux artifacts](https://v2.tauri.app/plugin/updater/). Therefore:

- enable updater only when runtime detects the direct AppImage channel;
- deb/Flatpak builds hide the in-app install action and show their package channel;
- update endpoint HTTPS, exact origin, static JSON with version/URL/signature; public key embedded in the app;
- private signing key lives only in maintainer/CI secret storage, with encrypted offline backup and documented rotation/recovery; never commit its path/value;
- refuse downgrade by default; offer a separately signed manual rollback procedure;
- publish SHA-256 checksums and a detached Linux artifact signature in addition to the mandatory Tauri updater signature;
- test signature corruption, truncated artifacts, wrong architecture, replayed manifests, offline/partial download and disk-full behavior;
- track and patch system WebKitGTK advisories through minimum-supported distro policy; the AppImage does not magically freeze the host web engine.

`latest.json` example shape is generated by release tooling, not edited manually:

```json
{
  "version": "1.0.0",
  "notes": "...",
  "pub_date": "2026-09-07T00:00:00Z",
  "platforms": {
    "linux-x86_64": {
      "signature": "CONTENTS_OF_SIG",
      "url": "https://releases.example/miaoyan-1.0.0-x86_64.AppImage"
    }
  }
}
```

### 11.4 Diagnostics

Follow the macOS local-first stance:

- `tracing` structured events into a 50-entry JSONL fault ring plus bounded rotated operational log (recommended 5 × 1 MiB);
- common funnel equivalent to `AppDelegate.trackError(error, context:)` with stable context/error codes;
- redact home/library absolute prefixes, URL userinfo/query, Authorization/cookies, PAT/API-key patterns, note content, filenames if “private paths” is enabled;
- diagnostics screen shows app/Tauri/WRY/WebKitGTK/GTK versions, distro, compositor/backend, package channel, watcher mode, last sync error code, index integrity and portal availability;
- “Export Diagnostics” creates a previewable archive only after user confirmation; never upload automatically;
- journal/syslog integration is optional and contains the same redacted fault summaries, not note content;
- crash dumps are opt-in and reviewed separately; no analytics SDK in v1.

## 12. Explicit semantic deltas from macOS

| Area | macOS 4.2.0 | Linux decision | User-visible consequence |
|---|---|---|---|
| iCloud | native/selected-folder support | omitted | Git or external folder provider instead |
| built-in Git | absent | new `origin/main` HTTPS sync | settings/status/conflicts are Linux-only initially |
| AI resolver | absent | optional manual conflict proposal | note content leaves device only after explicit confirmation |
| raw HTML | cmark unsafe HTML reaches preview | sanitized allowlist | active HTML/forms/scripts may no longer work |
| preview file access | WebView allowed read access to `/` | opaque root-confined custom protocol | absolute/local escape paths are blocked |
| remote media | renderer can load network resources | blocked by default; host opt-in | tracking images show placeholders |
| PlantUML | automatic official-server request | click/per-library consent, configurable endpoint | first render may require action |
| PicGo/PicList | loopback uploader with local fallback | fixed loopback Rust client, redirect/response limits | same workflow without browser-wide network permission |
| pins/cursor | xattrs | xattr + portable local DB fallback | works on filesystems without xattrs; metadata does not pollute library |
| atomic save | Darwin swap primitive | fsync + atomic replace + revision CAS/history | same safety goal, different rollback mechanism |
| Trash | app Trash/system Trash/Finder edge marker | app Trash + portal/FDO Trash + portable tombstone | file-manager recovery remains supported |
| titlebar/menu | AppKit/macOS | native desktop decorations/Linux menu | no traffic lights/global macOS menu |
| shortcuts | Command | Ctrl and user-approved portal globals | follows Linux conventions |
| window position/focus | AppKit can request | Wayland compositor authority | size/state restored; exact position not promised |
| tray/background | platform menu bar | optional, unavailable on some desktops | sync does not rely on tray |
| Finder/Terminal actions | Finder and macOS terminal launch | desktop opener plus fixed argv terminal adapter | wording/backend follows the desktop; no generic shell bridge |
| `miao` CLI | macOS `defaults`/`open` integration | Linux XDG config and local versioned app RPC | same commands, different activation/config transport |
| fonts/icons | Apple stacks/SF availability | system/fontconfig + licensed repository SVG | tiny glyph-metric differences across desktops |
| PDF | WKWebView/PDFKit custom pipeline | WebKitGTK/portal, direct export beta until gate | outline/pagination may differ; golden thresholds defined |
| updates | Sparkle direct + independent App Store | signed Tauri AppImage; distro-managed deb/Flatpak | updater behavior depends on package channel |

## 13. Delivery plan and estimates

Estimates assume one senior Rust/Tauri engineer, one frontend/editor engineer, part-time product designer and Linux QA. Ranges include tests and documentation but not waiting on upstream security fixes.

### Phase 0 — proof gates (2–3 weeks, must complete first)

- Tauri/WebKitGTK shell on GNOME/KDE, Wayland/X11, opaque visual prototype;
- CodeMirror IME/large-file/caret spike and Monaco fallback comparison;
- cmark-gfm → sanitizer → custom protocol exploit prototype;
- WebKitGTK print-to-PDF pagination/background/font proof;
- portal directory grant persistence in a Flatpak spike;
- git2 worker with a local adversarial HTTP Git server, but no production remote until advisory gate;
- baseline screenshots and macOS behavior fixtures.

Exit: written go/no-go for editor, preview, PDF, Flatpak and Git. Failure changes the architecture before feature construction.

### Phase 1 — local-first vertical slice (5–7 weeks)

- Tauri/Rust/Svelte project, typed code generation, capability allowlist;
- three-pane UI, theme/tokens, sidebar and note list;
- library picker/scan/nesting/symlink policy;
- CodeMirror open/edit/1.5 s autosave/owner-revision guard;
- notify reconciliation, Trash, history, recovery drafts, diagnostics skeleton;
- deb/AppImage unsigned development builds.

Exit: daily-use local editor survives kill, external edits, rename/delete races and IME matrix without data loss.

### Phase 2 — Markdown intelligence (5–7 weeks)

- cmark-gfm parity/frontmatter/GitHub Alerts;
- safe preview, local attachments and external links;
- search rank, pins, wikilinks/backlinks;
- Prettier and Clean Typography with cursor/undo/composition guards;
- import/orphan attachment management and loopback-only PicGo/PicList integration;
- remaining note actions, copy/share formats, single-file mode and Linux `miao` CLI.

Exit: corpus/golden parity and security tests pass; no unsolicited network traffic.

### Phase 3 — rich preview, slides and export (5–7 weeks)

- Mermaid, KaTeX, consented PlantUML;
- split/continuous fullscreen preview and synchronized scrolling;
- Reveal.js presentation;
- self-contained HTML and PDF/print pipeline, readiness protocol, outlines if gate passes.

Exit: Mermaid/images/math/PlantUML export together; paginated and continuous goldens accepted on baseline WebKitGTK.

### Phase 4 — Git, conflict UI and optional AI (6–8 weeks)

- Secret Service, Git settings and preflight budgets;
- manual/15m/exit scheduler and isolated worker;
- fast-forward/divergence/delete/binary/case conflict model and durable UI;
- optional compatible AI proposal flow, fake-server tests and privacy UX;
- dependency security gate re-run.

Exit: no-force end-to-end sync against GitHub-compatible HTTPS test servers, recovery from every interrupted phase, PAT absent from logs/process/UI.

### Phase 5 — release hardening (4–6 weeks)

- accessibility/localization, performance/fuzz/soak;
- signed AppImage updater, deb metadata, SBOM/notices, release playbook;
- supported-distro/compositor matrix and support bundle;
- external beta, data-loss/security triage and final visual QA.

Exit: signed x86_64 1.0 candidate. Add **3–5 weeks** for ARM64 and Flathub after core stability. Total: **27–38 engineering weeks** before contingency; budget **30–40**.

### Proposed MVP boundary

The first internal MVP is Phase 1. The first public beta should include Phases 1–3. The requested product is not feature-complete until Phase 4 and release hardening. Do not call a local editor without Git/conflict handling “parity” if product messaging promises the full scope.

## 14. Test and acceptance matrix

### 14.1 Environments

| Dimension | Required cells |
|---|---|
| distro | Ubuntu 22.04 and 24.04; Debian 12; Fedora current; one rolling distro smoke |
| desktop | GNOME, KDE Plasma; Xfce smoke; wlroots/COSMIC best-effort smoke |
| display | native Wayland; X11; XWayland where relevant |
| scale | 100%, 125%, 150%, 200%; mixed-DPI two-monitor manual test |
| theme/accessibility | light, dark, high contrast, text scale 200%, reduced motion, Orca screen reader |
| package | dev unpackaged, deb, AppImage; Flatpak when phase begins |
| filesystem | ext4, btrfs, case-sensitive; xattr-disabled; NFS/SMB poll mode; symlink loop/escape; low disk/inotify exhaustion |
| input | US keyboard, zh-Hans fcitx5 + IBus Pinyin, zh-TW Zhuyin, Japanese Mozc, Korean Hangul, emoji/dead keys |
| web engine | oldest supported distro WebKitGTK and current patched versions |

### 14.2 Functional suites

| Suite | P0 acceptance examples |
|---|---|
| storage | atomic save across crash points; missing target not recreated; delete retires callbacks; Trash recovery; failed move retains row |
| identity | selection/buffer divergence regression; concurrent external edit; rapid note switching during debounce |
| scan/watch | nested/symlink dedupe, loops, rename storms, editor truncate/replace patterns, root removal, poll fallback |
| history | 20 cap, 300 s throttle, whitespace duplicate, force-before-destructive, restore creates new head |
| search/links | exact 4/3/2/1 order, 100 cap, short/CJK/combining terms, Trash exclusion, rename/backlink invalidation |
| editor | composition/undo/caret, paste, drag/drop, huge thresholds, scroll sync, Prettier stale-result rejection |
| action parity | create/import/rename/move/duplicate/reload/sort/pin/copy/deep link/file-manager/terminal actions and preferences |
| CLI | `open/new/search/list/cat/update`, stale protocol, wrong-UID socket peer, GUI absent, paths with spaces/non-ASCII |
| uploads | PicGo success/offline/malformed/oversize/redirect/late callback; local fallback and no cross-note insertion |
| render | CommonMark/GFM official corpus, app fixtures, LF/CRLF frontmatter, alerts, raw HTML sanitizer, local/remote policies |
| rich content | Mermaid security, KaTeX trust, PlantUML consent/offline/bad SVG, media codec failure |
| export | images + diagrams readiness, continuous/paginated, page breaks, fonts, headers/footers, outline destination |
| Git | init/clone/config, equal/ahead/behind/diverged, auth/403/expired PAT, redirect, malicious pack/path/case, 25 MiB boundary |
| conflicts | text/binary/delete/rename/add/case; restart mid-resolution; stale Apply rejection; Keep Both |
| AI | disabled/no key, loopback, TLS/redirect, malformed/oversize/slow JSON, hostile note prompt, cancel, no content in diagnostics |
| updater | no update, valid, bad signature, wrong arch, truncated, disk full, rollback refusal, package-channel behavior |

### 14.3 Security and quality automation

- unit/property tests for path normalization, `openat` containment, revision state machine, search rank, frontmatter and conflict transitions;
- `cargo-fuzz` targets for cmark wrapper inputs, sanitizer, custom protocol parser, Git worker decoder and conflict metadata;
- static checks: `cargo fmt --check`, `cargo clippy --all-targets --all-features -- -D warnings`, `cargo test --all-features`, TypeScript check, ESLint, frontend unit tests;
- dependency checks: `cargo audit`, npm audit, license allowlist, SBOM generation; advisories reviewed rather than blindly waived;
- WebDriver/E2E using Tauri's Linux WebDriver path where stable, supplemented by component tests and manual IME/portal tests;
- network-deny preview test using a capture proxy and malicious Markdown corpus;
- reproducible clean build on baseline container, install/uninstall/upgrade smoke inside fresh VMs.

### 14.4 Performance budgets

Measure on a declared midrange 4-core/8 GiB VM with cold caches and release build:

| Scenario | Budget |
|---|---:|
| window first usable frame, 5k-note indexed library | p95 ≤ 1.5 s |
| select/open cached ≤100 KiB note | p95 ≤ 100 ms |
| select/open cold ≤1 MiB note | p95 ≤ 350 ms; skeleton by 100 ms |
| keystroke visual latency, normal note | p95 ≤ 16 ms, p99 ≤ 32 ms |
| autosave 100 KiB ext4 | p95 ≤ 100 ms off UI thread |
| interactive search, 50k notes/500 MiB text | p95 ≤ 150 ms after first query warmup |
| preview normal 500-line note | p95 ≤ 500 ms after debounce |
| idle memory, 5k-note library | target ≤ 250 MiB RSS; investigate >300 MiB |
| 30-minute edit/watch soak | zero lost bytes, duplicate notes or unbounded RSS growth |

These are release gates after benchmarking Phase 1; adjust only with recorded hardware, traces and product approval.

### 14.5 Visual acceptance

- reference screenshots from the same MiaoYan 4.2.0 states: all panes, sidebar collapsed, list collapsed, editor-only, split preview, dark, fullscreen preview, presentation, preferences, history and conflict UI;
- deterministic CI font set and viewport sizes; pixel diff threshold ≤1% outside font anti-alias masks, geometry diff ≤1 CSS px for defined tokens;
- manual paid-product review on GNOME and KDE for hover/focus/disabled states, truncated titles, 125/150% scale and CJK text;
- no translucency, no wholesale icon replacement, no macOS traffic lights, no horizontal document overflow.

## 15. Risks and mitigations

| Risk | Probability / impact | Mitigation / owner |
|---|---|---|
| CodeMirror IME defect in WebKitGTK | medium / critical | Phase 0 multi-IME gate; Monaco fallback; editor owner |
| libgit2 unpatched vulnerabilities | high now / critical | feature gate, isolated worker, redirect none, gix/system-Git fallback; security owner |
| PDF differs across WebKitGTK | high / high | bridge spike, golden matrix, portal fallback/beta label; export owner |
| Tauri frontend looks “webby” | medium / high | exact tokens, native decorations/dialogs, reference screenshots, design sign-off |
| raw HTML parity conflicts with security | high / high | documented profiles/placeholders, no full-trust escape hatch |
| Flatpak broad library/Git access | medium / high | portal persistent grants, separate manifest/review, ship after direct packages |
| watcher misses network events/inotify limits | high / high | event-as-hint reconciler, polling fallback/banner/full scan |
| filesystem normalization/case collisions | medium / high | preflight, byte-path storage, never auto-checkout collisions |
| system WebKit/font/codec fragmentation | high / medium | support matrix, minimum versions, fallbacks, notices/codec table |
| exit sync expectation cannot be guaranteed | high / medium | local save first, 5 s budget, next-launch status, truthful “best effort” wording |
| derived SQLite drifts | medium / medium | generation/revision checks, transactional deltas, rebuild path |
| scope is too large for one release | high / high | gates and vertical phases; public beta before Git, honest parity labeling |

## 16. Exact product questions requiring decisions

Recommended defaults are included so work can proceed without ambiguous design invention.

1. **Brand/app ID:** Is the Linux app named “MiaoYan” with `com.tw93.miaoyan-linux`, or must it share `com.tw93.miaoyan`? Recommended: Linux-specific ID to avoid state/update collisions.
2. **License/commercial distribution:** Will Linux be free/open-source, paid direct, or store-paid? This changes updater hosting, support obligations and Flathub suitability. Recommended technical default: same repository license, direct signed downloads.
3. **Library grant boundary:** May a symlink leave the selected root? Recommended: only if the resolved target is separately selected/granted as a library.
4. **Conflict-copy placement:** Keep `.miaoyan-conflicts` beside notes for external visibility/Git-independent recovery, or XDG state for a clean library? Recommended: keep the existing sibling convention but always Git-ignore it.
5. **Pin portability:** Should pins sync through Git? Recommended: no for v1; portable local DB fallback only, matching current xattr locality. A tracked metadata file would be a cross-platform product change.
6. **Search/frontmatter:** Should YAML keys/values be searchable? Recommended: no, matching visible-content expectations; add a metadata query syntax later.
7. **Raw HTML compatibility:** Which iframe origins are officially supported? Recommended: none by default; exact per-library HTTPS allowlist with a click gate.
8. **Remote images:** block, proxy, or auto-load? Recommended: block and offer per-library host allowlist; never proxy through an MiaoYan service.
9. **PlantUML:** official server by default, explicit first-use consent, or require custom endpoint? Recommended: explicit first-use consent with local endpoint option.
10. **Media codecs:** Which audio/video formats are promised? Recommended: promise only what the supported distro WebKitGTK/GStreamer matrix verifies; show external-open fallback.
11. **PDF 1.0:** Is portal “Save as PDF” acceptable if direct export fails the gate? Recommended: yes for beta, no for “full parity” stable claim.
12. **Git hosting:** any HTTPS Git server or GitHub only? Recommended: standards-compatible HTTPS origins, with GitHub in the certified matrix; do not infer GitHub-specific API.
13. **Git identity:** fixed name/email in settings or per-library? Recommended: global default plus per-library override; never infer from PAT username.
14. **Sync contents:** all files under library or only supported notes/attachments? Recommended: supported notes, `i/`, `files/` and user-visible ordinary files within budgets; explicit preview before first commit.
15. **25 MiB:** decimal 25,000,000 or binary 26,214,400 bytes? Recommended: **25 MiB = 26,214,400 bytes**, as encoded in the contract.
16. **Automatic merge:** may clean text three-way merges be committed automatically? Recommended: yes only when Git reports clean and no active dirty buffer; always create history first.
17. **Periodic lifecycle:** should closing the last window keep syncing? Recommended: no by default; explicit “keep running” opt-in with visible tray/notification support.
18. **Exit sync timeout:** recommended 5 s after a mandatory 3 s local flush. Is a prompt acceptable when unsynced? Recommended: do not block normal exit; status next launch.
19. **AI endpoint contract:** is `/v1/chat/completions` sufficient, or must Responses API variants be supported? Recommended: Chat Completions first for compatibility, adapter interface for later variants.
20. **AI privacy:** confirm one-time provider disclosure plus per-request summary. Recommended: required; no library-wide consent shortcut in v1.
21. **Supported locales:** English + zh-Hans only or macOS's five locales at launch? Recommended: English + zh-Hans beta, all macOS locales before stable parity claim.
22. **Supported architectures/formats:** x86_64 deb/AppImage first? Recommended: yes; ARM64 and Flathub after telemetry-free support demand is understood.
23. **Tray/global shortcut:** are these actual requirements or platform explorations? Recommended: optional tray, no required global shortcut.
24. **Visual sign-off environment:** which distro/desktop is canonical for screenshot approval? Recommended: Ubuntu 24.04 GNOME Wayland, with KDE Wayland as co-equal convention check.
25. **Linux `miao` install/activation:** must the CLI be on `PATH` by default, and may read-only commands run without the GUI? Recommended: deb installs `/usr/bin/miao`; Flatpak exposes a manifest-approved command invoked through Flatpak rather than writing host `/usr/bin`; AppImage offers an explicit install step; allow read-only headless commands through the shared Rust core.
26. **PicGo/PicList parity:** retain the current fixed local uploader in v1? Recommended: yes, disabled until chosen, loopback-only, with immediate local-attachment fallback.
27. **Open in Terminal:** auto-detect a small supported terminal set or require a preference? Recommended: desktop default when discoverable, otherwise an argv-array preference with validation and a clear unavailable state.

## 17. Implementation decision records to create

Before coding beyond Phase 0, record and approve:

- ADR-001 Tauri/Svelte/CodeMirror and IME benchmark result;
- ADR-002 filesystem identity, symlink confinement and portable atomic writes;
- ADR-003 preview origin, CSP, sanitizer profiles and custom asset protocol;
- ADR-004 SQLite-derived index and exact search compatibility;
- ADR-005 Git engine security gate and worker isolation;
- ADR-006 conflict state machine and AI data disclosure;
- ADR-007 WebKitGTK PDF bridge/fallback;
- ADR-008 package channels, updater ownership and signing recovery;
- ADR-009 licensed fonts/icons/codecs and third-party notices.

## 18. Source index

All external technical claims in this plan use primary project/specification/vendor sources. Versions are a point-in-time snapshot and must be rechecked when implementation starts.

### Tauri, WRY and WebKitGTK

- [Tauri prerequisites / WebKitGTK 4.1](https://v2.tauri.app/start/prerequisites/)
- [Tauri architecture](https://v2.tauri.app/concept/architecture/)
- [Tauri security](https://v2.tauri.app/security/)
- [Tauri capabilities](https://v2.tauri.app/security/capabilities/)
- [Tauri CSP](https://v2.tauri.app/security/csp/)
- [Tauri asset protocol scope](https://v2.tauri.app/security/asset-protocol/)
- [Tauri 2 Rust API](https://docs.rs/tauri/latest/tauri/)
- [WRY API/platform notes](https://docs.rs/wry/latest/wry/)
- [WebKitGTK 4.1 reference](https://webkitgtk.org/reference/webkit2gtk/stable/)
- [WebKitGTK WebContext/custom schemes](https://webkitgtk.org/reference/webkit2gtk/stable/class.WebContext.html)
- [WebKitGTK PrintOperation](https://webkitgtk.org/reference/webkit2gtk/stable/class.PrintOperation.html)

### Editor, Markdown and rendering

- [CodeMirror guide](https://codemirror.net/docs/guide/), [reference](https://codemirror.net/docs/ref/), [changelog](https://codemirror.net/docs/changelog/)
- [Monaco releases](https://github.com/microsoft/monaco-editor/releases)
- [cmark-gfm releases](https://github.com/github/cmark-gfm/releases)
- [Prettier browser API](https://prettier.io/docs/browser/) and [options](https://prettier.io/docs/options)
- [Mermaid usage/security levels](https://mermaid.js.org/config/usage.html)
- [KaTeX trust/strict options](https://katex.org/docs/options)
- [Reveal.js releases](https://github.com/hakimel/reveal.js/releases)

### Storage, desktop and packaging

- [`notify` caveats](https://docs.rs/notify/latest/notify/) and [`notify-debouncer-full`](https://docs.rs/notify-debouncer-full/latest/notify_debouncer_full/)
- [`rusqlite`](https://docs.rs/rusqlite/latest/rusqlite/), [SQLite FTS5](https://sqlite.org/fts5.html), [SQLite WAL](https://sqlite.org/wal.html)
- [XDG Base Directory 0.8](https://specifications.freedesktop.org/basedir/latest/)
- [XDG Desktop Portal](https://flatpak.github.io/xdg-desktop-portal/docs/), [FileChooser](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.FileChooser.html), [Trash](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Trash.html), [Print](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Print.html), [GlobalShortcuts](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.GlobalShortcuts.html)
- [`ashpd`](https://docs.rs/ashpd/latest/ashpd/)
- [Wayland protocols: canonical xdg-shell XML](https://gitlab.freedesktop.org/wayland/wayland-protocols/-/blob/main/stable/xdg-shell/xdg-shell.xml)
- [StatusNotifierItem](https://specifications.freedesktop.org/status-notifier-item/latest/status-notifier-item.html)
- [Tauri AppImage](https://v2.tauri.app/distribute/appimage/), [Debian](https://v2.tauri.app/distribute/debian/), [Flathub](https://v2.tauri.app/distribute/flatpak/), [Updater](https://v2.tauri.app/plugin/updater/)
- [Secret Service 0.2](https://specifications.freedesktop.org/secret-service/latest/), [`secret-service` Rust crate](https://docs.rs/secret-service/latest/secret_service/), [libsecret API](https://gnome.pages.gitlab.gnome.org/libsecret/)
- [Inter OFL](https://github.com/rsms/inter/blob/master/LICENSE.txt), [Noto CJK license record](https://github.com/notofonts/noto-cjk/blob/main/Sans/README-third_party.md)

### Git and optional AI

- [`git2` 0.21](https://docs.rs/git2/latest/git2/), [`RemoteRedirect`](https://docs.rs/git2/latest/git2/enum.RemoteRedirect.html), [libgit2 releases](https://github.com/libgit2/libgit2/releases)
- [libgit2 redirect advisory GHSA-2889-x8f6-mc4x](https://github.com/libgit2/libgit2/security/advisories/GHSA-2889-x8f6-mc4x)
- [libgit2 OOB advisory GHSA-pm24-4jhq-3xvm](https://github.com/libgit2/libgit2/security/advisories/GHSA-pm24-4jhq-3xvm)
- [`gix` 0.87 trust/features/gaps](https://docs.rs/gix/latest/gix/)
- [Git credential helpers](https://git-scm.com/docs/gitcredentials)
- [GitHub file-size limits](https://docs.github.com/en/repositories/working-with-files/managing-files/adding-a-file-to-a-repository)
- [GitHub credential security](https://docs.github.com/en/rest/authentication/keeping-your-api-credentials-secure)
- [Official OpenAI API overview/authentication/request IDs](https://developers.openai.com/api/reference/overview)

## 19. Verification checklist for this plan

Before accepting this document as an implementation brief:

- [x] research-time primary-source URLs HTTP-checked on 2026-09-07 (the illustrative `releases.example` URL is intentionally non-resolving);
- [x] command snippets syntax-reviewed; future Rust/frontend build commands are acceptance contracts and were not executed in this documentation-only task;
- [ ] product answers section 16, especially raw HTML, Git content, PDF gate, close/background behavior and release channels;
- [ ] rerun latest-version and advisory checks; never turn snapshot versions into loose `*` constraints;
- [ ] rerun the primary-link check when implementation starts and replace moved documentation URLs;
- [ ] confirm Tauri capability/AppManifest behavior against the exact 2.x patch selected;
- [ ] inspect all repository SVG and bundled JS/font licenses; generate notices list;
- [ ] capture macOS 4.2.0 fixture corpus/screenshots before upstream visual or renderer changes;
- [ ] execute Phase 0 gates before committing to public delivery dates;
- [ ] keep the Linux work isolated from macOS/iOS targets until shared syntax fixtures are intentionally extracted;
- [ ] make no upstream push from research/prototyping worktrees without maintainer review.

## 20. Final recommendation

Proceed, but treat this as a Linux product built around MiaoYan's file and interaction semantics—not as a WebView skin. Tauri is viable if the Rust boundary is kept narrow, CodeMirror passes the real CJK matrix, and preview content never shares privilege with the application shell. The opaque MiaoYan layout can be reproduced convincingly while native decorations, portals, Ctrl shortcuts and compositor authority keep it at home on Linux.

The critical path is not the three-pane UI. It is proving data integrity across editor ownership/watchers/Git, rendering raw Markdown without granting local authority, and delivering PDF behavior on heterogeneous WebKitGTK. Phase 0 exists to retire those risks early. Git must remain gated until its engine is demonstrably safe on the actual release date. With those constraints, the proposed architecture supports the requested feature set without importing iCloud, mobile architecture, a browser server or the rejected Liquid Glass direction.
