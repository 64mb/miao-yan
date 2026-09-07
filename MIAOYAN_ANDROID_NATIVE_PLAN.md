# MiaoYan Native Android: архитектура и план реализации

Дата актуализации: 2026-09-07

Статус: research завершён; первый исполняемый editor/preview-прототип находится в `MiaoYanAndroid/`. Каноническое хранилище Android подтверждено как app-private; SAF остаётся только границей явного Import/Export.

Checkpoint прототипа: Android 15+ (`minSdk 35`), одна root-библиотека, рекурсивный список и поиск заметок, безопасное UTF-8 редактирование с защитой IME composition, fail-closed сохранение по hash и ограниченный Markdown preview без JavaScript/raw HTML. Прототип собран и проверен на native AVD. Следующий checkpoint переводит библиотеку из первоначального SAF-spike в `filesDir/libraries/default`, добавляет production cmark-gfm, attachments, Room и Git. AI для Android исключён.

## 1. Scope и принятые ограничения

Цель — отдельное нативное Android-приложение, совместимое с MiaoYan на уровне файлов и поведения. Это не порт SwiftUI/iOS-кода и не Kotlin Multiplatform.

Первая продуктовая версия:

- Kotlin + Android SDK;
- одна app-private корневая папка библиотеки: `filesDir/libraries/default`;
- вложенные папки и заметки `.md`, `.markdown`, `.txt`;
- чтение, редактирование, preview, поиск, изображения и Trash;
- Git-синхронизация по HTTPS вручную и opt-in автоматически каждые 15 минут;
- только `origin/main`;
- авторизация Git: username + PAT;
- максимальный размер добавляемого или изменяемого вложения — 25 MiB;
- пользовательские метаданные, Git-служебные данные и секреты остаются локальными;
- CI/CD пока не входит в scope;
- Android AI conflict resolver не входит в scope и не планируется в текущем goal;
- системная light/dark theme, app/action icons и визуальный parity с macOS для editor/preview;
- локальные настройки шрифта и размера: небольшой лицензируемый набор и не более 10 фиксированных размеров.
- SAF используется только для явного импорта из выбранной пользователем папки и экспорта в неё; импортированная папка не становится live-library.
- отдельные toolbar actions: Typesetting, полноэкранный непрерывный Preview со значком видеокамеры как на macOS и послайдовый Presentation;
- телефонный single-pane и планшетный adaptive two-pane layout; multi-window не считается полноэкранным режимом.

Ручной и 15-минутный автоматический sync входят в один Git MVP. Pin/favorite и остальные UI metadata остаются device-local, как на macOS.

## 2. Главный архитектурный вывод

Заметки Android нужны самому MiaoYan, а Git требует настоящий POSIX working tree. Поэтому каноническая библиотека хранится в app-private storage и одновременно является Git working tree:

```text
filesDir/libraries/default — канонические пользовательские документы
    └── .git

SAF tree выбранный пользователем — только источник Import или назначение Export
```

Инварианты:

- `filesDir/libraries/default` — единственный live source of truth содержимого заметок.
- Git работает прямо с этой папкой; отдельной mirror-копии и двустороннего apply нет.
- `.git`, локальные metadata, Trash и служебные данные никогда не экспортируются через SAF.
- Room — только перестраиваемый индекс, не источник текста заметок.
- История версий, черновики, conflict backups и pin/favorite metadata находятся в app-private storage.
- Все операции записи сериализуются одним `LibraryMutationCoordinator`.
- Безопасные записи используют temporary sibling + atomic replace, когда это поддерживается файловой системой; существующий файл не воссоздаётся молча после исчезновения.
- App update сохраняет библиотеку, но uninstall/clear data удаляет её. Этот риск должен быть явно показан в настройках рядом с Git и Export.

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
| Фоновые задачи | WorkManager: opt-in periodic Git sync, минимум 15 минут |
| Markdown | cmark-gfm 0.29.0.gfm.13 через узкий JNI API |
| Git | libgit2 1.9.7 через узкий JNI API |
| Preview | Android WebView + `WebViewAssetLoader` |
| Секреты | Android Keystore + AES-256-GCM blobs в `noBackupFilesDir` |
| Шрифты | системные equivalents; JetBrains Mono как bundled open alternative с license attribution |
| DI | ручная constructor injection на MVP |

Версии нельзя задавать динамически. Перед началом реализации нужно повторно проверить совместимость AGP/Kotlin/Compose BOM и pin-ить конкретные версии в version catalog.

`minSdk 35` — принятое продуктовое решение. Поддержка начинается с Android 15. Это убирает legacy-ветки Android 8–14, сокращает WebView/IME/device matrix и позволяет использовать актуальные API без compatibility-кода. Отличия SAF-провайдеров изолированы в явных Import/Export и не влияют на повседневное редактирование или Git checkout.

## 4. Модули

```text
MiaoYanAndroid/
├── app                 Compose shell, navigation, wiring
├── feature/library     папки, список заметок, поиск, recent
├── feature/reader      Markdown preview
├── feature/editor      editor, highlighting, attachments
├── feature/presentation fullscreen preview и Reveal.js slides
├── feature/sync        Git settings, progress, conflicts
├── core/model          platform-neutral domain models
├── core/data           app-private filesystem repository, Room index, SAF import/export
├── core/markdown       cmark-gfm JNI, transforms, HTML shell
├── core/git            libgit2 JNI, sync state machine
└── core/security       Keystore and encrypted secret storage
```

Не дробить проект дальше до появления реальных циклических зависимостей или проблем времени сборки.

Поток состояния:

```text
filesDir library → LibraryRepository → Room derived index → Flow → ViewModel → Compose
                                           ↑
UI intent → use case → mutation lock → atomic filesystem mutation → hash verification
```

UI никогда не работает с `File`, `ContentResolver`, libgit2 или Room DAO напрямую. `ContentResolver` доступен только Import/Export boundary.

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

## 6. App-private storage и SAF Import/Export

При первом запуске приложение создаёт `filesDir/libraries/default`. Пользователь не выбирает live-root и приложению не нужен постоянный broad-storage grant.

Модель:

- `LibraryRepository` работает с относительными путями только внутри канонического root.
- Room хранит `relativePath`, size, mtime, content hash, title, snippet и backlinks.
- Полный scan — итеративный обход вне main thread, с cancellation и защитой от symlink escape/loops.
- File observer — лишь сигнал к пересканированию; correctness опирается на hash/generation.
- До create/rename проверяются дубликаты без учёта регистра, Unicode normalization и лимит UTF-8 имени.
- Запись существующей заметки выполняется fail-closed по expected hash и owner note ID через temporary sibling + replace.
- Удаление сначала переносит объект в app-private `Trash/`; restore использует локальный manifest исходного пути и fallback в root при конфликте/исчезновении папки.
- `Trash`, `.Trash`, `.git`, `i/` и служебные файлы исключены из обычного списка и поискового индекса согласно своему назначению.

Import/Export:

- пользователь явно выбирает папку через `ACTION_OPEN_DOCUMENT_TREE` только на время операции;
- Import валидирует каждый относительный путь, тип, symlink/submodule policy, расширение и лимит 25 MiB до записи;
- Import по умолчанию не затирает существующие файлы молча: показывает план и требует выбора при коллизиях;
- Export создаёт согласованный snapshot пользовательских заметок и attachments, исключая `.git`, Trash, Room, secrets и локальные metadata;
- streams копируются частями и проверяются hash; ошибка оставляет каноническую библиотеку неизменной и сообщает о частичном результате назначения.

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
- Typesetting повторяет macOS-действие над текущим owner-buffer: форматирует Markdown локально, сохраняет protected code/math/raw-HTML regions и применяет результат только если note ID/revision не изменились;
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
- asset handler обслуживает только текущую library session и не раскрывает странице настоящий filesystem path.

Asset handler декодирует путь один раз и отклоняет `..`, backslash, encoded slash, absolute path, `file:`, произвольный `content:` и выход за root.

Текущая MVP-политика raw HTML:

- базовые HTML-теги разрешены;
- `<script>` из заметки не выполняется;
- iframe не загружается автоматически; окончательный UX между click-to-load в жёстком sandbox и внешним браузером требует продуктового подтверждения;
- встроенные scripts приложения получают случайный CSP nonce;
- `connect-src`, `object-src` и `frame-src` запрещены.

Golden corpus должен прогоняться через Swift и Android renderers; сравнивается нормализованный HTML fragment, а не platform shell.

### Fullscreen и Presentation Mode

Это два разных режима и две разные кнопки. Значок видеокамеры означает режим просмотра, а не доступ к hardware camera:

- Fullscreen Preview (иконка видеокамеры, как на macOS) показывает текущий обычный cmark-gfm document единой непрерывной прокручиваемой простынёй, скрывает app chrome и системные bars через актуальные WindowInsets APIs, возвращается по Back/gesture и не меняет текст;
- Slide Presentation делит документ по отдельным строкам `---`, создаёт Reveal.js sections, использует только bundled/pinned Reveal.js и локальные `/i/` assets, поддерживает swipe/keyboard navigation и сохраняет номер текущего слайда;
- приложение не запрашивает `CAMERA` permission и не подключает CameraX/media-capture API;
- note-provided JavaScript не выполняется ни в одном режиме; Reveal.js запускается только как доверенный bundled script под nonce/CSP;
- rotation/configuration change и уход приложения в background сохраняют текущий режим/slide, но не удерживают Activity;
- на планшете основной экран использует list-detail/two-pane layout, а оба presentation-режима занимают всё доступное окно; Android multi-window остаётся поддержан и не форсируется в системный fullscreen.

## 9. Git sync на Android

Git работает прямо с `filesDir/libraries/default`: это одновременно каноническая библиотека и обычный private POSIX working tree. SAF не участвует в sync.

Sync-транзакция, общая для ручного и фонового запуска:

1. Захватить library mutation lock.
2. Flush активного editor и pending saves.
3. Снять hash snapshot канонической библиотеки и проверить path policy.
4. Commit локальных изменений.
5. Fetch только точного refspec `refs/heads/main:refs/remotes/origin/main`.
6. На короткое окно блокировать редактирование и убедиться, что snapshot не изменился; при изменении повторить локальный commit.
7. Проверить все incoming paths, entry types и sizes до изменения working tree.
8. Создать `refs/miaoyan/recovery/latest` и filesystem backup затрагиваемых незакоммиченных данных.
9. Сделать fast-forward либо подготовить merge без conflict markers в live working tree.
10. Применить проверенный результат атомарно и проверить hashes.
11. Обновить Room/UI.
12. Push только `refs/heads/main:refs/heads/main`.
13. Освободить lock.

Ручной запуск доступен из UI. Автоматический запуск — отдельный opt-in toggle с периодом 15 минут через WorkManager, только при наличии сети и валидной конфигурации. Период Android является inexact: Doze и battery policy могут отложить фактический запуск. Повторный запуск не пересекается с активной sync/mutation operation.

Сетевые ограничения:

- HTTPS only;
- embedded credentials в URL запрещены;
- PAT не записывается в URL, Git config или logs;
- certificate validation нельзя отключать;
- cross-host redirects запрещены;
- branch всегда `main`;
- SSH не входит в scope.

Критический Phase 0 gate: доказать Android CA trust, hostname validation и redirect behavior выбранной Git/TLS-реализации. Если это не доказано, нельзя принимать self-signed certificate «временно» или передавать PAT callback-у для другого host.

### Initial sync

- пустой remote + непустая библиотека → preview initial commit, затем push;
- пустая библиотека + непустой remote → preview файлов, затем apply;
- обе стороны непусты и имеют unrelated histories → ничего не объединять автоматически; показать только «Оставить локальную библиотеку» или «Заменить локальную библиотеку удалённой», предварительно создав recovery snapshot;
- одинаковая история → обычный fast-forward/merge flow.

### Конфликты

- merge сначала выполняется в in-memory index;
- conflict markers не попадают в live canonical working tree;
- показываются путь, local timestamp и remote timestamp;
- для текста доступны только `Use Local` и `Use Remote`;
- для binary доступны только local/remote;
- после ожидания проверяются commit OID и актуальный local-file hash;
- resolved merge commit имеет двух родителей;
- отдельного 3-way UI/resolver нет.

AI resolver на Android отсутствует: нет checkbox, endpoint/model/key/prompt и сетевой отправки содержимого заметок AI-провайдеру.

## 10. Секреты и backup

Создать неэкспортируемый AES-256-GCM key в Android Keystore. PAT хранить как encrypted blob в `noBackupFilesDir`. Ключ идентификации PAT — нормализованный HTTPS remote URL.

DataStore хранит только несекретные настройки:

- remote URL;
- author name/email;
- sync/UI preferences.

Из Auto Backup исключаются secrets, `.git`, recovery/conflict backups, Room index, local version history и сама библиотека. Канонические заметки восстанавливаются через Git или явный Export/Import; это исключает частичный platform backup без согласованной Git/Keystore state.

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
- conflict classification и local/remote resolution validation;
- Trash/restore manifest и atomic-write state machine.

Instrumented fake `DocumentsProvider` для Import/Export boundary должен моделировать:

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
- fullscreen enter/exit, Back, rotation, tablet two-pane и Reveal.js slide navigation;
- Typesetting owner/revision guard и protected Markdown regions;
- Gboard, Chinese Pinyin, Japanese и Korean IME;
- 1 MiB / 5000 lines / 64 KiB paragraph benchmarks;
- Git initial push/pull, fast-forward, clean merge, text/binary conflicts, delete/modify, unrelated histories, push rejection, concurrent local edit и crash на каждом filesystem transition;
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

- app-private filesystem CRUD/Trash и SAF Import/Export spike;
- libgit2 Android build, HTTPS trust store, redirect, ABI;
- cmark-gfm JNI parity;
- WebView CSP/raw HTML prototype;
- ADR по app-private canonical storage, raw HTML и initial sync.

Не продолжать Git-реализацию, если TLS/credential binding или безопасное применение incoming tree не доказаны.

### Phase 1 — reader, 2–3 недели

- создание app-private root и явный Import/Export;
- scan, Room index, folders, recent/search;
- GFM preview, frontmatter, wikilinks, `i/`;
- safe WebView и external links;
- large-file skeleton/read-only behavior.

### Phase 2 — editor и сохранность, 3–4 недели

- EditText editor и incremental highlighting;
- CJK composition guards;
- autosave с owner/hash generation;
- image paste, `i/`, `files/`;
- Typesetting action;
- Trash/restore;
- local history и external-change conflicts.

### Phase 3 — manual + periodic Git MVP, 3–5 недель

- direct app-private Git working tree и recovery snapshot;
- Keystore PAT;
- `origin/main`, path policy, 25 MiB;
- commit/fetch/merge/apply/push;
- recovery ref;
- local/remote conflict UI;
- ручная кнопка Sync и opt-in WorkManager каждые 15 минут.

### Phase 4 — visual/settings и platform polish, 2–3 недели

- adaptive app/action icons;
- системная light/dark theme и macOS color parity;
- лицензируемые bundled fonts и локальный font/size picker (до 10 размеров);
- adaptive tablet two-pane layout и корректный multi-window;
- отдельные fullscreen continuous Preview и Reveal.js slide Presentation modes;
- diagnostics/polishing.

### Phase 5 — beta hardening, около 2 недель

- provider/device/IME matrix;
- fuzz path/HTML handling;
- Room/DataStore migrations;
- backup/privacy validation;
- Play pre-launch report.

Оценка: 8–14 инженерных недель до уверенной beta для одного опытного Android-разработчика. Отказ от SAF как live storage убирает mirror/apply state machine; главные неопределённости теперь — TLS/Git conflict safety, WebView и IME.

## 13. Вопросы, на которые нужен конкретный продуктовый ответ

Ниже не абстрактная «семантика», а решения, меняющие контракт.

1. Iframe: после явного нажатия загружать внутри preview в жёстком sandbox без scripts/forms/popups/top-navigation или открывать системный браузер?
2. PlantUML: локальный renderer, пользовательский endpoint или не включать в MVP? Рекомендация: не включать в MVP, пока нет локального renderer.
3. Перемещение заметки между папками: переносить ли автоматически её `i/` attachments и разрешать collision rename? Рекомендация: переносить только реально referenced attachments, collision решать новым именем и переписывать ссылки транзакционно.
4. Git author identity: какие default name/email показывать до первого push? Рекомендация: обязательные поля без скрытых фиктивных значений.

Уже решено: canonical Android library хранится в `filesDir/libraries/default`, SAF используется только для Import/Export; remote media загружается только по нажатию; Git имеет ручной и opt-in 15-минутный запуск; pin/favorite локальны; Restore использует локальный manifest исходного пути с fallback в root; Android AI resolver отсутствует.

## 14. Решение о старте

Начинать следует не с полного UI, а с четырёх исполняемых spikes:

1. App-private atomic filesystem operations и SAF Import/Export на реальных providers.
2. libgit2 1.9.7 HTTPS/TLS без утечки credentials.
3. cmark-gfm JNI и общий golden corpus.
4. WebViewAssetLoader + CSP + локальные `i/` assets без раскрытия filesystem paths.

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
