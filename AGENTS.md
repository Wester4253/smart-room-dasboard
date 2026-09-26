# AGENTS.md

Kotlin/Compose Android app (e-ink todo dashboard) plus a bundled Home Assistant custom
integration. Targets Boox e-ink tablets, so UI constraints below are deliberate.

## Build state

`main` did not compile until 2026-09-26: 43 Kotlin errors in `ui/`, plus a test-source error.
That is now fixed, and `./gradlew :app:testDebugUnitTest` (8 tests) and
`./gradlew :app:assembleRelease` both pass. If you hit a compile error, it is yours — there is no
pre-existing backlog to hide behind.

The cause was duplicated, half-finished UI work, not one file. Worth knowing so you do not
reintroduce it:

- `SmartRoomApp.kt` is the nav host and is authoritative. Screens are called with a rich API
  (`state: TodoUiState`, `onOpen`, `onHandwriting`, multi-arg `onAdd`). **When you add a screen,
  write it against the call site in `SmartRoomApp.kt`, not against what seems natural.**
- `ui/CardDetailScreen.kt` was written to complete this. Editing, completing, moving between
  decks and deleting all live there; list and board screens only navigate to it.
- `KanbanBoardScreen` renders one `To do`/`Done` column pair **per deck** from
  `AppSettings.deckEntityIds()`. A todo whose `listEntityId` is not a configured deck is
  deliberately not shown on the board.
- `SettingsScreen.onBack` defaults to `onHome` because `SmartRoomApp` never passes it.

## Commands

```bash
./gradlew :app:testDebugUnitTest    # JVM unit tests (the only suite that exists), 8 tests
./gradlew :app:assembleDebug        # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # release APK -> app/build/outputs/apk/release/
./gradlew :app:lint                 # AGP default; no custom lint/format config exists
./gradlew :app:compileDebugKotlin   # fastest way to see if the tree compiles
./gradlew :app:testDebugUnitTest --tests '*HomeAssistantTodoRepositoryTest*'
```

- `--offline` works for `assembleDebug`/`assembleRelease` (all runtime deps are cached) but **not**
  for the test tasks: `junit` and `kotlinx-coroutines-test` are not in the offline cache. Run test
  tasks online, or they fail with `No cached version ... available for offline mode`.
- The unit tests are plain JUnit4 + `kotlinx-coroutines-test`. They cannot use Robolectric or
  instrumented APIs, which is why every Android dependency (`SettingsStore`, `SecureStorage`,
  `TodoLocalStore`, `HomeAssistantApi`) is an **interface** — the test file supplies
  `MemoryLocalStore`, `TestSettingsStore`, `TestSecureStorage`, `FakeApi`. Keep it that way;
  adding a concrete `android.*` type to a code path under test breaks the suite.
- `HomeAssistantTodoRepository` takes `listItems`/`mutateItem` as constructor lambdas
  (`TodoItemLister` / `TodoItemMutator`, 9 params) purely so the repository is testable without a
  socket. When you change either typealias you must update every lambda in the test file or the
  test source stops compiling.
- There is no `src/androidTest`. `testInstrumentationRunner` is declared but nothing uses it, so
  no device/emulator run is expected.

## Setup

- `local.properties` (gitignored) must contain `sdk.dir`. Requires **JDK 17** and Android
  **compileSdk/targetSdk 35**. AGP 8.5.2 / Kotlin 2.0.21 / Gradle 9.1.0.
- `gradlew` is a hand-rolled ~20-line shell shim, not the stock Gradle wrapper script. It works,
  but don't be surprised by its size.
- CI (`.github/workflows/build-apk.yml`) runs `./gradlew :app:assembleDebug --no-daemon` on push
  to `main` and uploads the APK. It does **not** run tests.
- **Release signing is a fallback, not real signing.** `app/build.gradle.kts` builds a `release`
  signingConfig but only uses it when `RELEASE_STORE_FILE` is set in `gradle.properties`
  (with `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; `.gitignore` now
  covers `*.jks`/`*.keystore`). With none of those set, `assembleRelease` signs with the **Android
  debug key** — installable for sideloading, but not publishable, and a different machine's debug
  key will not upgrade an existing install. Don't ship this artifact to anyone else.

## Git hygiene

`.gitignore` lists only `local.properties`, so 1067 of 1114 tracked files are build artifacts
(`app/build/`, `.gradle/`, `.kotlin/`, `build/`). This is accidental but currently committed.

- `git status` is ~1000 lines of noise. Scope diffs to source:
  `git status --short -- app/src custom_components .github '*.kts'`
- Never `git add -A` / `git add .` — you will stage build output.
- Branch off `main` for changes; CI builds on push to `main`.

## Architecture

Single Gradle module `:app`. No DI framework, no codegen, no Room, no migrations. Manual
construction in `MainActivity.onCreate` via a `ViewModelProvider.Factory`; `SmartRoomApp(viewModel,
MlKitDigitalInkOcrEngine())` takes concrete objects, not interfaces to resolve.

```
MainActivity -> TodoViewModel -> HomeAssistantTodoRepository -> {TodoLocalStore, SettingsStore, SecureStorage}
                                                        \-> HomeAssistantWebSocket (OkHttp)
                                                        \-> HomeAssistantApi (Retrofit, /api/states only)
ui/  SmartRoomApp is a hand-rolled `when(screen)` nav switch over a private AppScreen enum.
     There is no Navigation Compose. Handwriting "navigation" is a stashed
     `(String) -> Unit` callback plus the screen to return to.
```

### Offline queue and the remote-UID rule

The single most important invariant: **a local `Todo.id` is a random `UUID` until the first
refresh, and that UUID must never be sent to Home Assistant.**

- `HomeAssistantTodoRepository` writes the local store *first*, then appends a
  `PendingTodoOperation` and tries to drain the queue. On failure the failed op **and all later
  ops stay queued** (`syncPending` re-writes `pending.drop(index)`) so ordering is preserved.
  Failed mutations are surfaced as UI errors, never dropped.
- `refresh()` drains the queue *before* replacing the cache with the server list.
- `UPDATE`/`DELETE`/`MOVE` call `resolveRemoteItem` to look up the real HA `uid` by matching
  `uid ==`, then `summary == previousTitle`, then `summary == todo.title`. If nothing matches,
  `UPDATE` throws and stays queued on purpose.
- `TodoLocalStore.writePendingOperations` uses `commit()` (not `apply()`) and `check()`s the
  result — the queue must be durable before it is considered written.
- `MOVE` is implemented as add-to-destination then remove-from-source. It is not an atomic HA
  move.

### Home Assistant transport rules

These are load-bearing and enforced by tests named `... never falls back to the broken REST
get_items endpoint` / `... never calls todo get_items`:

- **Reads** go through the WebSocket command `todo/item/list` only. The REST `todo.get_items`
  service is a confirmed HA core bug (HTTP 500, home-assistant/core#113063). Do not add a REST
  fallback for listing — there is a test that fails if you do.
- **Writes** go through the bundled integration's `boox_smart_room/todo/{add,update,remove}`
  WebSocket commands, not REST services. If you change the payload shape, change
  `custom_components/boox_smart_room/__init__.py` in the same commit — the Kotlin side hand-builds
  JSON strings in `HomeAssistantWebSocket.mutateTodo` and there is no shared schema.
- Entity discovery uses `GET /api/states` and filters `todo.*`. `HomeAssistantApi` deliberately
  has *only* that one method.
- `defaultListTodoItems` retries the socket 3 times with a 750 ms delay, then probes REST
  `/api/states` purely to produce a better error message ("REST reachable -> WebSocket upgrade
  blocked"). `testConnection` is a raw OkHttp call, not Retrofit.
- Base URLs must be normalized before use: `String.normalizedBaseUrl()` strips a trailing `/api`
  and guarantees exactly one trailing `/` (Retrofit requires it).
- The HA token lives in Android Keystore AES/GCM under key `home_assistant_token`, in a
  `secure_settings` SharedPreferences file. It is only ever sent as a `Bearer` header. Never log
  it, never move it to `settingsStore`.

### E-ink UI constraints (violating these breaks the product)

- Target device is a Boox e-ink tablet: `SmartRoomTheme` in `ui/Theme.kt` deliberately sets
  `primary`/`secondary` to white and disables ripples. **Never fill a control black** —
  black-on-black, and some Boox firmware inverts. Add a new color role with that in mind.
- `InkCanvasView` accepts every tool type on purpose: the Note Air2 Plus capacitive pen reports
  as a *finger*, the EMR pen as a *stylus*. Do not filter by `toolType`.
- `InkCanvasView` reflectively calls Boox `setUpdateMode` / `EpdController.applyTransientUpdate`
  for fast refresh, and forces `LAYER_TYPE_SOFTWARE`. ML Kit ink recognition needs stable stroke
  point ordering, which is why `monotonic()` rewrites event timestamps and single-point strokes
  are padded to two points in `toOcrStrokes()`. Don't "simplify" these.
- `HomeScreen` loads the HA dashboard with `?external_auth=1` and a `JavascriptInterface` bridge.
  Putting the token in the URL is explicitly rejected.
- The ML Kit English Digital Ink model (~20 MB) is downloaded on first use via
  `RemoteModelManager`; recognition failures degrade to `OcrResult.Unavailable` with a manual-text
  fallback rather than throwing.

## Home Assistant integration

`custom_components/boox_smart_room/` is a HACS integration (`hacs.json`, `content_in_root: false`,
HA >= 2024.6.0). It registers WebSocket commands in `async_setup` and delegates to the built-in
`todo` domain services. There is no Python packaging, no test suite, and no linting config for it
— validate changes by reading, or against a live HA instance.
