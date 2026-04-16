# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

**_NOTE:_**  At the end of every task, Codex will review your work

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
- All user-facing strings must use `stringResource()` from compose resources — never hardcode text
- Use `start`/`end` padding, not `left`/`right` (RTL support)
- Use Material 3 color roles from `MaterialTheme.colorScheme` — never hardcode colors
- Use Material 3 typography from `MaterialTheme.typography` — never hardcode text styles
- Provide `contentDescription` on all meaningful icons/images for accessibility
- UiState should be a flat `data class` with `_uiState.update { }` pattern
- One-time events (navigation, snackbar) should use separate `SharedFlow`, not stored in UiState