# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

## Discussions

If I push back and you still think you're right, hold the position and
explain why. Don't cave just because I disagreed. If I've actually
changed your mind with a real argument, say what specifically changed it.

## Debugging & root cause

When you hit a freeze, ANR, crash, or unexplained behaviour, do not ship
a workaround that hides the symptom without identifying the cause first.
Capture concrete evidence — a stack trace, an ANR dump, a profiler
sample, a reproducible test — and prove what's broken before fixing it.
If you can't capture evidence, the fix is to install the instrumentation
that will (a watchdog, a logger, a tombstone reader, an `adb logcat`
capture) — not to patch around the symptom and move on.

Symptom patches to avoid:

- Wrapping a state read in `remember { }` because "without it the screen
  freezes" — the underlying read is doing something expensive or
  reactive on every recomposition; fix that, don't snapshot it.
- Adding `try { … } catch (_: Exception) { }` around code that's
  actually misbehaving, so the exception stops surfacing.
- Adding a `delay()`, an extra `LaunchedEffect`, or a manual redraw
  trigger to make a UI glitch "go away" without explaining why it
  helped.
- Reverting or hiding the feature that exposed the bug, when the bug
  itself is still there.

Each of these makes the bug invisible at the cost of leaving the cause
in place to resurface elsewhere later. If you find yourself reaching for
one of these patterns, stop and write down what you actually observed,
what you suspect, and what evidence you'd need to confirm — then go get
that evidence.

## Project Overview

Homebase Chat — a Kotlin Multiplatform (KMP) chat application targeting Android, iOS, Desktop (
macOS/Windows/Linux), and Web (partial). Built with Compose Multiplatform, using MVVM architecture
with Koin DI.

## Build & Run Commands

```bash
# Android
./gradlew androidApp:installDebug
./gradlew androidApp:assembleRelease    # requires HOMEBASE_KEYSTORE_PASS env var

# Desktop
./gradlew desktopApp:run
./gradlew desktopApp:hotRunJvm --auto   # hot reload

# iOS — open iosApp/ in Xcode and run

# Web (currently disabled in settings.gradle.kts)
./gradlew webApp:wasmJsBrowserDevelopmentRun --no-configuration-cache
```

## Testing

```bash
# JVM tests (common + auth + chat + api modules)
./gradlew homebase-chat:jvmTest homebase-auth:jvmTest homebase-common:jvmTest homebase-api:jvmTest --rerun-tasks

# Platform-specific
./gradlew androidApp:testDebugUnitTest
./gradlew desktopApp:desktopTest
./gradlew iosSimulatorArm64Test          # requires booted iOS simulator
```

## Module Architecture

```
homebase-api          — Core layer: HTTP client (Ktor), database (SQLDelight), crypto, sync engine
    ↑
homebase-common       — Shared UI components, theme, settings, notifications, permissions, image/audio utils
homebase-auth         — Authentication screens and logic
homebase-chat         — Chat features: conversations, messaging, groups, media, encryption services
    ↑
homebase-core         — App orchestration: navigation (AppNavHost), DI setup (Koin), top-level screens
    ↑
androidApp / desktopApp / iosApp  — Platform entry points
```

**Module namespace:** `id.homebase.*` — api, core, chat, feed (android app)

## KMP Source Set Convention

Each module follows the standard KMP layout:

- `src/commonMain/kotlin/` — Shared code (bulk of logic)
- `src/androidMain/kotlin/` — Android implementations (OkHttp, ExoPlayer, SQLCipher)
- `src/jvmMain/kotlin/` — Desktop implementations (VLC-J, JDBC SQLite)
- `src/nativeMain/kotlin/` — iOS implementations (Darwin networking, native SQLite)
- `src/webMain/kotlin/` — Web implementations (partial)

Use `expect`/`actual` declarations for platform-specific code. The flag `-Xexpect-actual-classes` is
enabled.

## Key Technology Choices

- **DI:** Koin — all modules registered in `homebase-core/.../di/AppModule.kt`
- **Database:** SQLDelight (`OdinDatabase`) with SQLCipher encryption on Android, encrypted JDBC on
  Desktop
- **Networking:** Ktor client with platform-specific engines (OkHttp/Darwin/CIO)
- **Navigation:** Compose Navigation via `AppNavHost` in homebase-core
- **Serialization:** kotlinx.serialization (JSON)
- **Images:** Coil3
- **State:** ViewModels with StateFlow, separate `*UiState` data classes
- **Logging:** Kermit
- **Notifications:** KMPNotifier (cross-platform)

## Build Configuration

- **Java 17** required (Temurin distribution in CI)
- **Gradle config cache** enabled
- **Version catalog:** `gradle/libs.versions.toml` — all dependency versions managed here
- **Android:** compileSdk 36, minSdk 27, targetSdk 36
- **Kotlin:** 2.3.10, Compose Multiplatform 1.10.2

## iOS Framework

homebase-core exports as `ComposeApp` framework, transitively exporting homebase-api and
homebase-common. Other modules export individual frameworks (`homebase-commonKit`,
`homebase-authKit`, `homebase-chatKit`).

## Android Emulator/Device Logs (adb)

`adb` must be in PATH (ships with the Android SDK under `platform-tools/`).

**Package names:** debug = `id.homebase.feed.dev`, release = `id.homebase.feed`.
The examples below use the debug package; substitute for release as needed.

### On-device log file (preferred — contains all app-level Kermit logs)

```bash
# Read the log file directly
adb shell run-as id.homebase.feed.dev cat files/logs/homebase.log

# Copy to local machine
adb shell run-as id.homebase.feed.dev cat files/logs/homebase.log > homebase.log

# Tail recent entries (filter out stack trace lines)
adb shell run-as id.homebase.feed.dev cat files/logs/homebase.log | grep -v "^\tat " | tail -50
```

The `run-as` prefix is required because the app's data directory is not world-readable.
Note: `run-as` only works on debug builds (or devices with root).

### Logcat (system-level, includes non-Kermit logs)

```bash
# Dump recent logs for the app
adb logcat -d --pid=$(adb shell pidof id.homebase.feed.dev)

# Clear buffer before a fresh capture
adb logcat -c
```

## Desktop App Logs (JVM / Android Studio `desktopApp:run`)

The Desktop App writes its `homebase.log` to the platform-specific app data directory
(determined by `JvmFileSystemUtil.getAppDataDirectory()`).

**Debug build** folder name = `HomebaseChatDev`, **Release** = `HomebaseChat`.

| OS      | Path                                                        |
|---------|-------------------------------------------------------------|
| Windows | `%APPDATA%\HomebaseChatDev\logs\homebase.log`               |
| macOS   | `~/Library/Application Support/HomebaseChatDev/logs/homebase.log` |
| Linux   | `~/.homebase-chat-dev/logs/homebase.log`                    |

```bash
# Windows (Git Bash / MSYS2)
cat "$APPDATA/HomebaseChatDev/logs/homebase.log"

# macOS / Linux
cat ~/Library/Application\ Support/HomebaseChatDev/logs/homebase.log   # macOS
cat ~/.homebase-chat-dev/logs/homebase.log                              # Linux
```

### Windows — installed (MSIX / Microsoft Store) build

The installed desktop app runs in an MSIX container, so `%APPDATA%` is redirected into the package's sandbox. The inner folder is still named `HomebaseChatDev` (the installed build uses the dev `buildConfigField`). Start from `%LOCALAPPDATA%\Packages` and search — the publisher-hash portion of the package name may change with re-signing:

```bash
# Git Bash / MSYS2 — find it from the stable root:
find "$LOCALAPPDATA/Packages" -iname "homebase.log" 2>/dev/null

# Example current path on this machine (publisher hash may differ):
cat "$LOCALAPPDATA/Packages/HomebaseChat_6x99c57gn1sg8/LocalCache/Roaming/HomebaseChatDev/logs/homebase.log"
```

```powershell
# PowerShell equivalent
Get-ChildItem -Path "$env:LOCALAPPDATA\Packages" -Filter homebase.log -Recurse -ErrorAction SilentlyContinue
```

## CI/CD

GitHub Actions workflows in `.github/workflows/`:

- `build-check.yml` — assembleDebug + createDistributable on push/PR to main
- `test.yml` — runs platform-specific tests (JVM, desktop, iOS simulator)
- `lint.yml` — code linting
- `build-android-release.yml`, `build-ios-release.yml`, `build-mobile-release.yml` — release builds

Do NOT use slash (/) in Git branch names

## UI & Design Quality

All UI code must follow **Material 3** guidelines and the **kmp-compose-multiplatform** skill. Before
writing or modifying any screen/composable, verify:

- Use `Icons.AutoMirrored.*` for directional icons (back arrows, forward) — never `Icons.Default.ChevronLeft`
- Use `collectAsStateWithLifecycle()` — never `collectAsState()` for ViewModel StateFlows
- All user-facing strings must use `stringResource()` from compose resources — never hardcode text.
  This includes overflow chips and badges (e.g. `"+$n"`, `"$count items"`) — `homebase-common`'s
  `ArchitectureTest.kt` runs Konsist against every Composable on JVM CI and fails the build if it
  sees a `Text("…")` / `Text(text = "…")` literal, regardless of `$` interpolation. Build the
  string outside the composable (e.g. `stringResource(MR.string.foo_more, n)`) or pass a variable
  in. The pre-existing `moments_detail_shared_with_more` (`+%1$d`) is the canonical resource for
  the "+N" overflow case if you need another.
- Use `start`/`end` padding, not `left`/`right` (RTL support)
- Use Material 3 color roles from `MaterialTheme.colorScheme` — never hardcode colors
- Use Material 3 typography from `MaterialTheme.typography` — never hardcode text styles
- Provide `contentDescription` on all meaningful icons/images for accessibility
- UiState should be a flat `data class` with `_uiState.update { }` pattern
- One-time events (navigation, snackbar) should use separate `SharedFlow`, not stored in UiState

## Compose & Flow gotchas

- **Don't write `snapshotFlow { ... }.distinctUntilChanged()`** — `snapshotFlow` already
  dedups internally with structural equality (`!=`) before emitting, so the trailing
  `.distinctUntilChanged()` runs the *same* comparison a second time. For scalar samples
  it's just waste; for samples like `Pair<Int, List<...>>` the doubled O(n) `List.equals`
  on every snapshot commit can stall the Compose UI dispatcher (Main) for seconds during
  bursty mutations like `LazyListState.scrollToItem` (build 1394 watchdog stack landed
  here at `ConversationContent.kt:817`). If you genuinely need a different equality (e.g.
  comparing only one field of a heavy value), shape the snapshotFlow block to *return*
  that key — don't bolt distinctUntilChanged on top.

- **Don't use `LazyListState.firstVisibleItemIndex` as an array index without
  clamping.** Compose's idiom for "land at the bottom on first frame, no flash" is
  `LazyListState(firstVisibleItemIndex = Int.MAX_VALUE)` — LazyColumn clamps the
  sentinel during its first measure pass, but anything reading the field *before*
  that measure runs (a `snapshotFlow {}` body, a `derivedStateOf`, a save-scroll
  effect on the same frame the state was created) gets `Int.MAX_VALUE` back. Using
  that as `for (i in firstVisibleIndex downTo 0)` walks ~2.1B iterations and
  freezes the UI dispatcher for seconds (build 1419 watchdog landed exactly here
  in `ConversationContent.kt`'s floatingDateLabel snapshotFlow with
  `idx=2147483647 items=0`). Use the
  `LazyListState.boundedFirstVisibleItemIndex(itemsSize: Int)` extension in
  `id.homebase.core.util.ScrollPosition.kt` — it returns `null` for an empty list
  and a clamped index otherwise.

## Strings & Unicode

User-entered text (messages, descriptions, names, link previews) can contain emoji and other
non-BMP characters that Kotlin `String` stores as UTF-16 surrogate pairs. Chopping such a
string with `take(n)`, `substring(0, n)`, `dropLast`, `subSequence`, etc. can split a
surrogate pair in half and produce a lone surrogate that breaks rendering and downstream
serialization.

- To truncate user content to a length budget, use `String.truncateToCodePoints(n)` from
  `id.homebase.api.util.StringExtensions` — it advances past surrogate pairs.
- For avatar initials from a display name, use `String.initials()` from
  `id.homebase.core.util.StringExtensions` — it splits on whitespace and returns
  first-of-first + first-of-last uppercased. Do not write `name.take(2).uppercase()`.
- `take`/`substring` remain correct for known-ASCII content: URLs, hex/base64, UUIDs,
  device tokens, byte arrays.

## Adding New Top-Level Features (Add-on Apps)

When adding a self-contained feature that surfaces as an icon in the bottom navigation bar
(Vault-style — onboarding flow, extend-permissions dialog, settings toggle for icon
visibility, optional biometric gate), follow the recipe in
[`ADDING_ADDON_APPS.md`](ADDING_ADDON_APPS.md). It covers preferences with stable UUIDs,
routing, `AppNavHost` wiring, `AuthConnectionCoordinator` drive subscription, DI, and the
expect/actual biometric layer.

## Adding a New Typed Message Kind

When adding a new chat message kind (poll, doodle, sticker — anything whose descriptor
rides on the message header rather than as a payload), follow the recipe in
[`ADDING_TYPED_MESSAGE_KIND.md`](ADDING_TYPED_MESSAGE_KIND.md). It covers reserving a
`ChatProtocol` dataType integer, the `MessageContent` sealed-interface subtype with its
nullable-descriptor parse-failure contract, choosing an `ActionPolicy`, parser/bubble/
composer/attachment-sheet wiring, the strings the bubble needs, and the `Unknown` chip
that gives older receivers a visible "please update the app" fallback. Existing kinds:
Event (`dataType = 210`) and DiceRoll (`dataType = 212`).

**Don't duplicate envelope fields in the descriptor.** The HomebaseFile envelope already
carries the message identity, sender, and timestamp — your descriptor JSON must NOT
repeat them:

- No `xxxId` — the HomebaseFile uniqueId already identifies the message.
- No `createdByOdinId` / authorId — read `HomebaseFile.fileMetadata.originalAuthor`.
- No `createdAtUtcMs` — read the message's `userDate` (or `fileMetadata.created`).

Duplicating these wastes the 7 KB `MaxHeaderContentBytes` budget, lets the two copies
drift, and forces every consumer to decide which one to trust. Also keep user-text caps
tight (e.g. title ≤80 codepoints, description ≤280 codepoints) so the descriptor stays
comfortably under 7 KB at the maximum slot/option count.