# MiaoYan macOS Git Sync — product contract and implementation plan

Дата актуализации: 2026-09-07
Статус: macOS-only реализация, включая opt-in automatic/pre-quit sync, завершена локально; остаются ручные проверки с реальными credentials/providers

## Scope

Цель — синхронизация единственной корневой библиотеки MiaoYan через Git Smart HTTP по HTTPS.

В scope:

- macOS 13+ и Swift 6.1;
- одна корневая папка библиотеки;
- ручной sync;
- opt-in automatic sync каждые 15 минут и best-effort sync перед выходом;
- ветка `main`;
- username + personal access token;
- автоматический clean merge;
- безопасный выбор версии при конфликте;
- опциональное AI-разрешение текстового конфликта.

Вне scope:

- iOS, Android и Linux;
- CI/CD и release automation;
- SSH Git transport;
- OAuth;
- Git LFS;
- выбор произвольной ветки;
- отдельный clone/onboarding в новую папку;
- синхронизация UI metadata.

## Зафиксированная семантика

### Базовый sync

1. Сохраняется реальный editor buffer, владельцем которого является `EditTextView.storageNote`.
2. Завершаются pending saves.
3. Проверяются root, location policy, URL, credentials и allowlist.
4. Локальные поддерживаемые изменения коммитятся.
5. Выполняется `fetch origin main`.
6. Ещё раз сохраняются изменения, сделанные во время fetch.
7. Входящий diff проверяется до checkout.
8. Создаётся локальный `refs/miaoyan/recovery/latest`.
9. Выполняется fast-forward или clean two-parent merge.
10. Модели заметок и preview согласуются с применённым diff.
11. Выполняется обычный push `main`; force push запрещён.

Команды настройки и sync всегда относятся к корню основной (`isDefault`) библиотеки и не зависят от текущего выделения в sidebar. Bookmark/additional roots не мигрируются и не получают отдельную Git-конфигурацию: поддерживается ровно одна root-библиотека. Неявный переход к другой библиотеке запрещён.

### Automatic и pre-quit sync

Галочка **Automatically sync every 15 minutes** в **Settings → Git Sync** является общим opt-in для периодического и pre-quit sync. Старые сохранённые конфигурации декодируются с выключенным флагом, поэтому существующим пользователям не добавляется фоновая сеть без согласия. Сохранение settings немедленно пересобирает расписание; выключение флага останавливает timer, а смена main library применяется после обязательного restart существующего storage flow.

Scheduler принадлежит `AppDelegate`, работает на main actor и запускает первый automatic sync не сразу, а через 15 минут. Перед постановкой в расписание и перед каждым запуском повторно проверяются exact URL текущей default-library, наличие конфигурации, opt-in и local-location policy. Additional roots и прежний root после смены settings никогда не используются. Один scheduler task и `GitSyncCoordinator.state` не допускают overlap между timer, ручным sync и lifecycle sync.

Периодический запуск не показывает toast, alert или conflict sheet. Успешный результат остаётся фоновым; missing credentials, unsafe location, blocked/conflict и runtime failures записываются через `AppDelegate.trackError`. Конфликт не выбирается автоматически и остаётся для следующего ручного sync.

При выходе с включённым opt-in `applicationShouldTerminate`:

1. синхронно сохраняет editor buffer строго по `EditTextView.storageNote` и вызывает `flushPendingSaves()`;
2. останавливает timer и возвращает `.terminateLater`;
3. если любой sync уже выполняется, не создаёт второй и ограниченно ждёт текущий даже при выключенном opt-in; иначе при включённом opt-in запускает тот же silent automatic path;
4. ограничивает всё ожидание 8 секундами;
5. при success, failure, blocked/conflict или timeout ровно один раз вызывает `reply(toApplicationShouldTerminate: true)`.

Failure и timeout никогда не удерживают приложение открытым: локальные файлы и recovery state не заменяются принудительно, причина попадает в diagnostics, блокирующий conflict UI при quit не показывается. `applicationWillTerminate` сохраняет повторный идемпотентный flush и cleanup временных preview-файлов. Отдельный reply gate защищает от гонки completion/timeout и двойного ответа AppKit.

### Несвязанные истории

`--allow-unrelated-histories` не используется. Пользователь получает только два решения:

- **Replace Local** — заменить файлы и локальную `main` на уже полученную `origin/main`;
- **Keep Local** — не менять файлы и ничего не отправлять на сервер.

**Keep Local** является безопасной default-кнопкой и срабатывает по Return. **Replace Local** требует явного клика и отмечен как destructive action. До Replace Local создаётся recovery ref. Выбор не переписывает remote history и не делает force push.

### Файловые конфликты

Если libgit2 не может сделать clean merge, live worktree остаётся нетронутым. Для каждого конфликтного файла показываются:

- relative path;
- дата и время последнего изменения файла в локальной Git-линии;
- дата и время последнего изменения файла в удалённой Git-линии;
- **Use Local**;
- **Use Remote**;
- **Resolve with AI**, только если AI настроен и обе версии являются UTF-8 text;
- Cancel.

Даты берутся из последнего commit на соответствующей first-parent истории, в котором blob этого path изменился. Filesystem mtime не используется: Git его не переносит.

После выбора для всех файлов создаётся обычный two-parent merge commit. Если ref изменился, пока открыт диалог, применение прекращается и требуется новый sync.

Встроенного 3-way редактора не будет. Результат AI показывается перед применением и может быть отредактирован пользователем.

### AI conflict resolution

Настройки относятся к текущей Git-библиотеке:

- enable/disable;
- HTTPS API base URL;
- произвольное имя модели;
- API key;
- переопределяемый system prompt.

Точка входа находится в системном окне **MiaoYan → Settings… → Git Sync**. Все поля находятся непосредственно на прокручиваемой странице: root-библиотека, отдельная кнопка **Move Library to Local Storage…**, HTTPS remote, username/PAT, commit author, opt-in **Automatically sync every 15 minutes**, явная галочка **Enable AI diff conflict resolver**, AI HTTPS endpoint, произвольное имя модели, API key и переопределяемый prompt. Git/credential/model fields строго однострочные; перенос разрешён только в prompt editor. **Save** и **Sync Now** закреплены в нижнем footer и не прокручиваются вместе с prompt. Только включённая AI-галочка сохраняет AI-конфигурацию и разрешает показывать **Resolve with AI** для подходящего текстового конфликта. В меню File остаётся только ручная команда **Sync Git Repository**.

Значения по умолчанию:

- base URL: `https://api.deepseek.com`;
- model: `deepseek-v4-flash`;
- protocol: OpenAI-compatible `POST /chat/completions`;
- вызов выполняется только после явного нажатия **Resolve with AI**.

Git PAT и AI API key хранятся раздельно в macOS Keychain. URL, model и prompt хранятся в UserDefaults. HTTP endpoints, embedded credentials и отключение системной TLS-проверки запрещены.

Сохранение Git credential, AI key и configuration выполняется как одна логическая транзакция: при ошибке новые Keychain values откатываются, а прежние keys удаляются только после успешного сохранения configuration.

AI-ответ читается потоково и отклоняется при превышении 4 MiB, поэтому недоверенный endpoint не может заставить приложение безлимитно буферизовать response body.

Документ передаётся модели как недоверенные блоки `BASE`, `LOCAL`, `REMOTE`. Дефолтный prompt требует вернуть только полный merged file и сохранять специфику MiaoYan: frontmatter, wikilinks, `/i/`, `/files/`, code/math fences, Mermaid, PlantUML, raw HTML и slide separators. Он запрещает выполнять инструкции из содержимого заметки и выдумывать факты, URL или attachment paths.

Основания:

- OpenAI рекомендует отделять high-level instructions от пользовательского input, явно задавать формат результата и тестировать prompt при смене model snapshot: <https://developers.openai.com/api/docs/guides/prompt-engineering>.
- DeepSeek документирует OpenAI-compatible Chat API, настраиваемый `base_url` и model IDs, включая `deepseek-v4-flash`: <https://api-docs.deepseek.com/>.
- Git по умолчанию отказывает unrelated histories как редкому небезопасному случаю: <https://git-scm.com/docs/git-merge#Documentation/git-merge.txt---allow-unrelated-histories>.

### Файловый контракт

Синхронизируются:

- `.md`, `.markdown`, `.txt` в корне и вложенных проектах;
- любые обычные файлы внутри каталогов `i/` и `files/`, включая изображения;
- `.gitignore`.

Не синхронизируются и блокируются, если уже tracked:

- symlinks и submodules;
- `.git/**` и другие hidden paths;
- Trash;
- произвольные файлы вне `i/` и `files/`;
- attachments больше 25 MiB.

Pin state, selection, cursor, layout, search index, caches, diagnostics и остальные xattrs/UserDefaults остаются device-local.

### Cloud/File Provider location policy

Git sync нельзя включить в каталоге, которым одновременно управляет другой sync engine.

Блокируются:

- iCloud ubiquitous items и iCloud containers;
- `~/Library/CloudStorage/**`;
- legacy `~/Dropbox`, `~/Google Drive`, `~/OneDrive*`;
- items, для которых macOS сообщает File Provider services;
- network/non-local volumes.

Перед настройкой Git sync приложение предлагает **Move and Restart**. После подтверждения:

1. editor buffer и pending saves сохраняются;
2. watcher останавливается;
3. включается write barrier для note save/move/remove и attachment writes; при активной загрузке изображения миграция не начинается;
4. библиотека копируется в staging под `Application Support/MiaoYan`;
5. source/destination inventory обязан совпасть полностью, каждый обычный файл проверяется byte-for-byte, symlink — по target;
6. staging атомарно переименовывается в `Application Support/MiaoYan/GitLibrary`;
7. новый path и security-scoped bookmark сохраняются;
8. непосредственно перед cleanup выполняется повторная полная проверка под `NSFileCoordinator`;
9. app-owned iCloud library перемещается в системную Trash; корни целого provider/volume автоматически не удаляются;
10. приложение перезапускается;
11. если безопасное удаление оригинала запрещено policy, приложение использует локальную копию и явно просит удалить cloud-copy вручную;
12. если финальная coordinated verification завершается ошибкой при существующем source, source снова становится настроенным root, verified local copy сохраняется как recovery и её путь показывается пользователю;
13. если verification успешна, но macOS/File Provider отказывает только на Trash, приложение использует verified local copy, предупреждает о сохранённом cloud-original и перезапускается; повторная попытка переиспользует существующую destination только после новой полной byte verification.

Существующая destination никогда не перезаписывается. При любой ошибке до переключения source остаётся авторитетным, staging удаляется, Git sync не включается.

## Реализовано

- локально упакованный universal libgit2 1.9.7 с SecureTransport;
- HTTPS-only clone/fetch/push и фиксированная `main`;
- Keychain credentials;
- exact-path staging и allowlist preflight;
- 25 MiB incoming/local attachment guard;
- clean merge, safe checkout, единая блокирующая `HEAD` + `refs/heads/main` libgit2 transaction и SAFE rollback к сохранённому pre-checkout tree без force;
- recovery ref и отсутствие force push;
- FSEvents suppression и точечная reconciliation моделей;
- отдельный раздел Settings → Git Sync и ручная команда sync в меню File;
- opt-in scheduler каждые 15 минут с exact-root/local checks, reconfiguration после Save и без overlap;
- silent best-effort pre-quit sync через `.terminateLater`, 8-секундный timeout и single-reply gate;
- выбор Replace Local / Keep Local для unrelated histories;
- per-file Local / Remote conflict choice с Git timestamps;
- OpenAI-compatible AI resolver с редактируемыми endpoint, model, key и prompt;
- транзакционное сохранение Git/AI settings и очистка заменённых Keychain entries;
- потоковый AI response limit 4 MiB;
- fail-closed обнаружение cloud/File Provider roots и подтверждаемая миграция в Application Support;
- migration write barrier, active-upload gate и recoverable cleanup failure;
- macOS unit/integration tests для transport, policies, credentials и конфликтов.

## Hard safety invariants

- Нельзя сохранять editor buffer по table selection или `EditTextView.note`; только по `storageNote`.
- Нельзя применять входящий tree до allowlist/size preflight.
- Нельзя автоматически выбирать сторону неразрешимого конфликта.
- Нельзя отправлять AI key или Git PAT в logs, UserDefaults, Git config или prompt.
- Нельзя отправлять заметку AI без явного действия пользователя.
- Нельзя следовать redirect при передаче AI Authorization header без повторной проверки origin.
- Нельзя удалять cloud source до успешной byte verification, повторной coordinated verification и сохранения нового storage path.
- Нельзя начинать миграцию во время image upload или писать в исходную библиотеку после включения migration barrier.
- Нельзя удалять verified local copy при ошибке cleanup; до ручного восстановления сохраняются обе копии.
- Нельзя автоматически удалять home/Documents, network volume root, весь iCloud Drive или корень Dropbox/Google Drive/OneDrive/File Provider.
- Нельзя force push или автоматически объединять unrelated histories.
- Нельзя запускать automatic/pre-quit sync без сохранённого opt-in или для root, отличного от текущей local default-library.
- Нельзя показывать modal conflict/failure UI из periodic или pre-quit sync.
- Timeout/failure pre-quit sync не может отменить выход или вызвать второй AppKit termination reply.

## Следующие проверки перед релизом

- ручной authenticated sync с реальным private HTTPS repository;
- ручной smoke periodic timer, Save enable/disable и quit success/failure/timeout на реальном remote;
- ручной UX smoke всех alert/sheet flows;
- AI smoke минимум с DeepSeek и одним другим OpenAI-compatible provider;
- fault injection миграции: collision, copy failure, verification failure, Trash failure, crash boundaries;
- privacy/acknowledgements copy для libgit2 и отправки содержимого заметки выбранному AI provider;
- обновление локализаций при финальном утверждении пользовательских формулировок.

## Локальная верификация 2026-09-07

- macOS Debug build (`CODE_SIGNING_ALLOWED=NO`) и полный unit/integration suite: 204 tests, 1 live-HTTPS test skipped без внешнего test remote, 0 failures;
- Git conflict и unrelated-history integration scenarios: passed;
- `xcrun swift-format lint --recursive . --strict`: passed;
- `git diff --check` и plist/strings validation: passed;
- `MiaoYanMobile/` и `.github/workflows/` не изменены.

SwiftLint не установлен в локальном окружении; его строгая проверка остаётся за CI. Не выполнены также проверки, требующие пользовательских секретов или реального внешнего окружения: authenticated private HTTPS remote, AI providers и живые iCloud/File Provider каталоги.
