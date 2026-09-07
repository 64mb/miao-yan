# MiaoYan Android prototype

Native Android 15+ prototype for validating the first local-library flow:

1. Select exactly one library root through Storage Access Framework.
2. Persist access to that tree and recursively list `.md`, `.markdown`, and `.txt` notes.
3. Search and open a note.
4. Edit through a platform `EditText` that does not replace text during IME composition.
5. Preview a safe, intentionally bounded Markdown subset with JavaScript and raw HTML disabled.
6. Save only if the document hash still matches the opened version, then verify the written bytes.

The user-selected SAF tree remains canonical. There is no broad storage permission and no network permission.

## Build

```bash
cd MiaoYanAndroid
./gradlew test lint assembleDebug
```

The project pins AGP 9.4.0, Gradle 9.6.0, Kotlin/Compose compiler 2.4.10, Compose BOM 2026.06.00, `compileSdk 36`, `targetSdk 36`, and `minSdk 35`. The prototype deliberately stays on the newest SDK platform currently available from the installed stable Android SDK channel; moving to API 37 is a dependency-only follow-up once that platform is available locally.

## Deliberate prototype limits

- The preview renderer is not the production cmark-gfm JNI implementation yet.
- Inline images, note creation/rename/Trash, Room indexing, Git sync, and AI conflict resolution are not included in this first executable slice.
- SAF save recovery is best effort. The production journal and crash recovery state machine remain Phase 0 work.
