# MiaoYan Native Android: архитектура и план реализации

Дата актуализации: 2026-09-07

Статус: research завершён; первый исполняемый SAF/editor/preview-прототип находится в `MiaoYanAndroid/`.

Checkpoint прототипа: Android 15+ (`minSdk 35`), одна root-библиотека через SAF, рекурсивный список и поиск заметок, безопасное UTF-8 редактирование с защитой IME composition, fail-closed сохранение по hash и ограниченный Markdown preview без JavaScript/raw HTML. Прототип собран и проверен на native AVD; production cmark-gfm, attachments, Room, Git и AI остаются следующими этапами.

## 1. Scope и принятые ограничения

Цель — отдельное нативное Android-приложение, совместимое с MiaoYan на уровне файлов и поведения. Это не порт SwiftUI/iOS-кода и не Kotlin Multiplatform.

Первая продуктовая версия:

- Kotlin + Android SDK;
- одна выбранная корневая папка библиотеки;
- вложенные папки и заметки `.md`, `.markdown`, `.txt`;
- чтение, редактирование, preview, поиск, изображения и Trash;
- ручная Git-синхронизация по HTTPS;
- только `origin/main`;
- авторизация Git: username + PAT;
- максимальный размер добавляемого или изменяемого вложения — 25 MiB;
- пользовательские метаданные, Git-служебные данные и секреты остаются локальными;
- CI/CD пока не входит в scope.

AI conflict resolver и фоновая синхронизация — следующая фаза после безопасного ручного Git MVP.

## 2. Главный архитектурный вывод

Android Storage Access Framework (SAF) выдаёт `content://` URI, а не POSIX-путь. Провайдер может быть локальным, облачным, медленным, не поддерживать seek или атомарный rename. libgit2, напротив, требует обычный working tree.

Поэтому нужны два разных хранилища:

```text
SAF tree — канонические пользовательские документы
    ⇅ импорт/apply с SHA-256 и recovery journal
filesDir/git/<library-id>/worktree — приватный Git mirror
    └── .git
```

Инварианты:

- SAF-папка — единственный постоянный источник содержимого заметок.
- `.git` никогда не копируется в SAF-папку.
- Room — только перестраиваемый индекс, не источник текста заметок.
- Git mirror, история версий, черновики, recovery journal, conflict backups и pin/favorite metadata находятся в app-private storage.
- Все операции записи сериализуются одним `LibraryMutationCoordinator`.
- Незавершённая операция после process death обнаруживается и докатывается либо откатывается до показа библиотеки.

## 3. Рекомендуемый стек

Стартовая зафиксированная матрица:

| Компонент | Решение |
|---|---|
| Язык | Kotlin 2.4.10 |
| Build | AGP 9.4.0, Gradle 9.6, JDK 17 |
| SDK | `compileSdk 37`, `targetSdk 37`, `minSdk 35` (Android 15) |
| UI | Jetpack Compose BOM 2026.08.00, Material 3 |
| Редактор | `AppCompatEditText`/`EditText` внутри `AndroidView` |
| Состояние | ViewModel + coroutines + Flow/StateFlow, UDF |
| Настройки | Preferences DataStore 1.2.1 |
| Индекс | Room, только stable channel |
| Фоновые задачи | WorkManager, не используется в manual-sync MVP |
| Markdown | cmark-gfm 0.29.0.gfm.13 через узкий JNI API |
| Git | libgit2 1.9.7 через узкий JNI API |
| Preview | Android WebView + `WebViewAssetLoader` |
| Секреты | Android Keystore + AES-256-GCM blobs в `noBackupFilesDir` |
| DI | ручная constructor injection на MVP |

Версии нельзя задавать динамически. Перед началом реализации нужно повторно проверить совместимость AGP/Kotlin/Compose BOM и pin-ить конкретные версии в version catalog.

`minSdk 35` — принятое продуктовое решение. Поддержка начинается с Android 15. Это убирает legacy-ветки Android 8–14, сокращает WebView/IME/device matrix и позволяет использовать актуальные API без compatibility-кода. Ограничение не отменяет provider matrix: SAF-провайдеры по-прежнему отличаются возможностями и атомарностью операций.

## 4. Модули

```text
MiaoYanAndroid/
├── app                 Compose shell, navigation, wiring
├── feature/library     папки, список заметок, поиск, recent
├── feature/reader      Markdown preview
├── feature/editor      editor, highlighting, attachments
├── feature/sync        Git settings, progress, conflicts
├── core/model          platform-neutral domain models
├── core/data           SAF repositories, Room index, journals
├── core/markdown       cmark-gfm JNI, transforms, HTML shell
├── core/git            libgit2 JNI, sync state machine
└── core/security       Keystore and encrypted secret storage
```

Не дробить проект дальше до появления реальных циклических зависимостей или проблем времени сборки.

Поток состояния:

```text
SAF → LibraryRepository → Room derived index → Flow → ViewModel → Compose
                              ↑
UI intent → use case → mutation lock → journal → SAF → hash verification
```

UI никогда не работает с `ContentResolver`, libgit2 или Room DAO напрямую.

## 5. Кроссплатформенный файловый контракт

Android обязан сохранить следующие правила MiaoYan:

- один root библиотеки;
- относительные UTF-8 пути с `/` как разделителем в domain/Git-слое;
- заметки: `.md`, `.markdown`, `.txt`;
- `i/` рядом с заметкой для inline-изображений;
- Markdown-ссылка на локальное изображение: `![](/i/<name>)`;
- `files/` для других вложений;
- `Trash` и `.Trash` исключены из обычного списка, поиска и Git;
- YAML frontmatter удаляется на каждой rendering/export surface с одинаковой LF/CRLF-семантикой;
- wikilinks `[[note]]`, backlinks и recursive search;
- GFM tables, task lists, strikethrough, autolinks и GitHub Alerts;
- fenced/inline code, math, Mermaid, PlantUML, raw HTML и `---` slide separators;
- изображения, video, iframe и таблицы в preview имеют `max-width: 100%`; горизонтальный scroll всей страницы запрещён;
- локальная история: максимум 20 snapshots, не чаще одного автоматического snapshot за 5 минут;
- исчезнувший существующий файл не создаётся заново autosave-операцией;
- editor buffer всегда сохраняется только своему `ownerNoteId`.

Git path policy:

- разрешены заметки, содержимое любых `i/` и `files/`, корневая `.gitignore`;
- запрещены absolute paths, `..`, backslash, control chars и пути длиннее 4096 bytes;
- запрещены hidden paths, кроме `.gitignore`;
- запрещены symlink, submodule и non-regular entries;
- `Trash`/`.Trash` запрещены;
- 25 MiB проверяются для added/modified attachment, но не для deletion.

## 6. SAF: чтение, индексирование и безопасная запись

Корень выбирается через `ACTION_OPEN_DOCUMENT_TREE`. Приложение сохраняет persistable read/write grant и явно обрабатывает его отзыв.

Модель:

- `LibraryId` генерируется локально и связывается с `treeUri`.
- Room хранит `relativePath`, document ID/URI, MIME, size, mtime, content hash, title, snippet и backlinks.
- Полный scan — итеративный BFS вне main thread, с cancellation и ограниченной параллельностью.
- В hot path использовать `ContentResolver` + `DocumentsContract`; recursive `DocumentFile` оставить только для простых boundary-операций.
- `ContentObserver` — только сигнал для повторного scan: полагаться на полноту уведомлений cloud-provider нельзя.
- `lastModified` недостаточно надёжен; перед destructive write сравнивать content hash/generation.
- Читать и писать streams частями, не предполагая seekable descriptor.
- До create/rename проверять дубликаты, регистр и Unicode normalization.

Транзакция записи:

1. Захватить mutation lock.
2. Сохранить preimage и intent в app-private recovery journal.
3. Сверить expected hash/generation.
4. Выполнить доступную provider-операцию.
5. Повторно прочитать результат и проверить SHA-256.
6. Обновить Room только после успешной проверки.
7. Пометить journal entry завершённой и удалить preimage по retention policy.

Удаление сначала переносит документ в библиотечный `Trash/`. Если `moveDocument` недоступен: copy → verify → delete. При любой ошибке исходный документ и UI row сохраняются.

## 7. Редактор и CJK/IME

Compose используется для экранов, но редактор MVP строится на платформенном `EditText` через `AndroidView`. Это даёт точный контроль над `Editable`, composing spans, selection, paste и инкрементальной подсветкой на больших документах.

Правила:

- пока активна IME composition, нельзя заменять весь `Editable`;
- нельзя менять spans внутри composing range;
- нельзя выполнять autosave/binding внешнего текста до завершения composition;
- после завершения composition binding догоняет актуальный текст;
- smart quotes/dashes и изменения, способные переписать Markdown, отключены;
- подсветка пересчитывается по изменённому абзацу, глобальный fence pass — только когда он нужен;
- вставка изображения копирует файл в соседний `i/`, затем вставляет `/i/<name>`;
- autosave несёт `ownerNoteId`, buffer revision и expected file generation;
- несовпадение owner, исчезновение файла или внешнее изменение приводит к fail-closed, а не overwrite.

Performance degradation thresholds переносятся из macOS:

- документ более 1 MiB или абзац более 64 KiB — упрощённая подсветка без дорогих code regex;
- более 5000 строк — упрощённая подсветка без code-block анализа;
- более 2000 строк — упрощённая подсветка с сохранением code blocks.

Бюджеты на reference mid-range device:

- cached library first frame ≤ 400 ms;
- открытие заметки до 1 MiB ≤ 500 ms либо сразу показывается skeleton;
- typing latency p95 ≤ 32 ms;
- scan, Markdown render и hashing никогда не выполняются на main thread.

## 8. Markdown preview и безопасность

Для семантического совпадения используется тот же cmark-gfm, а не похожая CommonMark-библиотека.

Pipeline:

1. Strip frontmatter с одинаковой LF/CRLF-семантикой.
2. Защитить code/math regions от постобработки.
3. Запустить cmark-gfm с согласованными extensions/options.
4. Применить transforms: Alerts, wikilinks, math placeholders и cleanup.
5. Переписать локальные `src`/`href`.
6. Вставить fragment в Android HTML shell с общими CSS/assets.

WebView использует `https://appassets.androidplatform.net` через `WebViewAssetLoader`:

- `allowFileAccess = false`;
- `allowContentAccess = false`;
- file/universal URL access выключены;
- mixed content запрещён;
- `addJavascriptInterface` не используется;
- неизвестная навигация блокируется;
- внешние HTTP(S)/mailto links открываются системно;
- bundled Mermaid/highlight/math scripts pin-ятся;
- asset handler обслуживает только текущую library session и не раскрывает странице настоящий `content://` URI.

Asset handler декодирует путь один раз и отклоняет `..`, backslash, encoded slash, absolute path, `file:`, произвольный `content:` и выход за root.

Рекомендованная MVP-политика raw HTML:

- базовые HTML-теги разрешены;
- `<script>` из заметки не выполняется;
- iframe заменяется placeholder с действием «Открыть внешне»;
- встроенные scripts приложения получают случайный CSP nonce;
- `connect-src`, `object-src` и `frame-src` запрещены.

Golden corpus должен прогоняться через Swift и Android renderers; сравнивается нормализованный HTML fragment, а не platform shell.

## 9. Git sync на Android

Git работает только с приватным mirror. SAF нельзя checkout-ить напрямую.

Ручная sync-транзакция:

1. Захватить library mutation lock.
2. Flush активного editor и pending saves.
3. Импортировать SAF snapshot в mirror с path-policy validation.
4. Commit локальных изменений.
5. Fetch `origin/main`.
6. Повторно проверить SAF hashes и импортировать изменения, возникшие во время fetch.
7. На короткое окно блокировать редактирование и сделать второй commit при необходимости.
8. Проверить все incoming paths и sizes до checkout/apply.
9. Создать `refs/miaoyan/recovery/latest`.
10. Сделать fast-forward либо merge в mirror.
11. Применить diff в SAF через recovery journal и проверить hashes.
12. Обновить Room/UI.
13. Push `refs/heads/main:refs/heads/main`.
14. Освободить lock.

Сетевые ограничения:

- HTTPS only;
- embedded credentials в URL запрещены;
- PAT не записывается в URL, Git config или logs;
- certificate validation нельзя отключать;
- cross-host redirects запрещены;
- branch всегда `main`;
- SSH не входит в scope.

Критический Phase 0 gate: доказать Android CA trust, hostname validation и redirect behavior выбранной libgit2 TLS-сборки. Если это не доказано, нельзя принимать self-signed certificate «временно»; нужен отдельный JGit/custom transport spike.

### Initial sync

- пустой remote + непустая библиотека → preview initial commit, затем push;
- пустая библиотека + непустой remote → preview файлов, затем apply;
- обе стороны непусты и имеют unrelated histories → ничего не объединять автоматически; показать только «Оставить локальную библиотеку» или «Заменить локальную библиотеку удалённой», предварительно создав recovery snapshot;
- одинаковая история → обычный fast-forward/merge flow.

### Конфликты

- merge сначала выполняется в in-memory index;
- conflict markers не попадают ни в SAF, ни в live mirror worktree;
- показываются путь, local timestamp и remote timestamp;
- для текста доступны `Use Local`, `Use Remote`, опционально `Resolve with AI`;
- для binary доступны только local/remote;
- после ожидания проверяются commit OID и актуальный SAF hash;
- resolved merge commit имеет двух родителей;
- отдельного 3-way UI/resolver нет.

AI resolver:

- включается отдельным checkbox;
- настройки: HTTPS endpoint, model name, API key, editable prompt;
- отправляются path, BASE, LOCAL, REMOTE;
- вызов выполняется только после явного действия пользователя;
- redirect запрещён, timeout 60 секунд, response limit 4 MiB;
- ответ показывается как diff и применяется только после подтверждения;
- prompt требует сохранить frontmatter, wikilinks, attachment paths, code, math, Mermaid, PlantUML, raw HTML и slide separators;
- экран явно предупреждает, что содержимое конфликта будет отправлено пользовательскому endpoint.

## 10. Секреты и backup

Создать неэкспортируемый AES-256-GCM key в Android Keystore. PAT и AI API key хранить отдельными encrypted blobs в `noBackupFilesDir`. Ключ идентификации PAT — нормализованный HTTPS remote URL.

DataStore хранит только несекретные настройки:

- library tree URI;
- remote URL;
- author name/email;
- AI endpoint/model/prompt;
- sync/UI preferences.

Из Auto Backup исключаются secrets, Git mirror, `.git`, recovery/conflict journals, Room index и local version history. Иначе после restore возможны ciphertext без исходного Keystore key и рассинхронизированный Git/SAF state.

Диагностика — локальный JSONL ring buffer на 50 событий без analytics SDK, с единым error funnel по аналогии с macOS.

## 11. Тестовая стратегия

JVM unit tests:

- LF/CRLF frontmatter;
- path normalization/traversal/encoded separators;
- `i/`, `files/`, Trash и extension policy;
- 25 MiB, 25 MiB + 1, deletion;
- remote URL normalization;
- buffer-owner/generation guard;
- wikilinks и duplicate titles;
- conflict classification и AI response validation;
- recovery journal state machine.

Instrumented fake `DocumentsProvider` должен моделировать:

- slow/cloud streams;
- `lastModified = 0`;
- non-seekable descriptors;
- отсутствие move/rename/delete capability;
- permission revocation;
- duplicate names;
- partial write/delete failure;
- provider death и process restart.

Отдельные suites:

- WebView CSP, traversal, external navigation и отсутствие horizontal page scroll;
- Gboard, Chinese Pinyin, Japanese и Korean IME;
- 1 MiB / 5000 lines / 64 KiB paragraph benchmarks;
- Git initial push/pull, fast-forward, clean merge, text/binary conflicts, delete/modify, unrelated histories, push rejection, concurrent SAF change, crash на каждом journal transition;
- TLS, redirect и credential-leak tests на контролируемом HTTPS server.

Локальный verification gate до появления CI:

```bash
./gradlew test lint
./gradlew connectedCheck
./gradlew :benchmark:connectedCheck
```

Плюс native cmark/libgit2 tests и ручная provider/IME matrix.

## 12. Этапы

### Phase 0 — feasibility, 1–2 недели

- SAF spike: Files, Google Drive, минимум один сторонний provider;
- libgit2 Android build, HTTPS trust store, redirect, ABI;
- cmark-gfm JNI parity;
- WebView CSP/raw HTML prototype;
- ADR по mirror, raw HTML и initial sync.

Не продолжать Git-реализацию, если TLS или transactional SAF apply не доказаны.

### Phase 1 — reader, 2–3 недели

- выбор root и persistable grant;
- scan, Room index, folders, recent/search;
- GFM preview, frontmatter, wikilinks, `i/`;
- safe WebView и external links;
- large-file skeleton/read-only behavior.

### Phase 2 — editor и сохранность, 3–4 недели

- EditText editor и incremental highlighting;
- CJK composition guards;
- autosave с owner/hash generation;
- image paste, `i/`, `files/`;
- Trash/restore;
- local history и external-change conflicts.

### Phase 3 — manual Git MVP, 3–5 недель

- private mirror и import/apply journal;
- Keystore PAT;
- `origin/main`, path policy, 25 MiB;
- commit/fetch/merge/apply/push;
- recovery ref;
- local/remote conflict UI.

### Phase 4 — AI и background, 2–3 недели

- AI conflict proposal + diff acceptance;
- opt-in WorkManager sync;
- adaptive tablet layout;
- diagnostics/polishing.

### Phase 5 — beta hardening, около 2 недель

- provider/device/IME matrix;
- fuzz path/HTML handling;
- Room/DataStore migrations;
- backup/privacy validation;
- Play pre-launch report.

Оценка: 10–16 инженерных недель до уверенной beta для одного опытного Android-разработчика. Главная неопределённость — SAF и TLS libgit2, а не Compose UI.

## 13. Вопросы, на которые нужен конкретный продуктовый ответ

Ниже не абстрактная «семантика», а решения, меняющие контракт.

1. Raw HTML: принимаем безопасное отличие от macOS — HTML разрешён, но scripts не выполняются, iframe открывается внешне? Рекомендация: да.
2. Remote media: загружать внешние images/video автоматически или только по нажатию? Рекомендация: блокировать по умолчанию ради privacy.
3. PlantUML: локальный renderer, пользовательский endpoint или не включать в MVP? Рекомендация: не включать в MVP, пока нет локального renderer.
4. Background sync: manual-only MVP или сразу periodic? Рекомендация: manual-only, background позже и только opt-in.
5. Pin/favorite: допустимо ли оставить только на конкретном Android-устройстве? Рекомендация: да, согласно правилу «метаданные локальные».
6. Перемещение заметки между папками: переносить ли автоматически её `i/` attachments и разрешать collision rename? Рекомендация: переносить только реально referenced attachments, collision решать новым именем и переписывать ссылки транзакционно.
7. Restore из Trash: возвращать в исходную папку через локальный manifest или всегда в root? Рекомендация: manifest + исходная папка, fallback в root.
8. Git author identity: какие default name/email показывать до первого push? Рекомендация: обязательные поля без скрытых фиктивных значений.
9. AI privacy: достаточно ли disclosure непосредственно перед первым AI resolve и ссылки в Settings? Рекомендация: оба места плюс явное подтверждение первого вызова.

## 14. Решение о старте

Начинать следует не с полного UI, а с четырёх исполняемых spikes:

1. SAF journal/apply на реальных providers.
2. libgit2 1.9.7 HTTPS/TLS без утечки credentials.
3. cmark-gfm JNI и общий golden corpus.
4. WebViewAssetLoader + CSP + локальные `i/` assets.

После прохождения этих gates можно создавать production-модули Phase 1.

## 15. Основные источники

- [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Secure cryptography](https://developer.android.com/privacy-and-security/cryptography)
- [Load local WebView content](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)
- [WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
- [Compose text input](https://developer.android.com/develop/ui/compose/text/user-input)
- [WorkManager task scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent)
- [AGP versions](https://developer.android.com/build/releases/about-agp)
- [Kotlin releases](https://kotlinlang.org/docs/releases.html)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [cmark-gfm releases](https://github.com/github/cmark-gfm/releases)
- [libgit2 repository and Android build notes](https://github.com/libgit2/libgit2)
- [libgit2 build guide](https://libgit2.org/docs/guides/build-and-link/)
