# Plan 006: Fix two iOS parity gaps — the dead notification-settings button and the missing URI-scheme guard in fileExists

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-common/src/nativeMain/kotlin/id/homebase/core/notifications/SystemNotificationSettings.native.kt homebase-chat/src/nativeMain/kotlin/id/homebase/chat/services/FileExistence.native.kt`. If either in-scope file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P1
- Effort: S
- Risk: LOW
- Depends on: none
- Category: bug (cross-platform parity)
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
Two iOS-only `actual` implementations silently no-op where Android and Desktop do the right thing, so iPhone users get broken behaviour. (A) The "System default sound" row on the Notification Settings screen is tappable on iOS but does nothing — its `actual` returns an empty `{ /* TODO */ }` lambda, while Android opens `ACTION_APP_NOTIFICATION_SETTINGS` and Desktop opens OS prefs. (B) iOS `fileExists` calls `NSFileManager.fileExistsAtPath` with no URI-scheme guard, so a `content://`, `file://`, or `http(s)://` reference returns `false`; `LocalVideoContextStore` then falsely treats the local video context as gone and evicts it — Android and Desktop both short-circuit `if (path.contains("://")) return true` first. Both are one-line `actual` fixes with a verified copy-able exemplar already in the repo.

## Current state

### Finding A — dead notification-settings button (iOS)
`homebase-common/src/nativeMain/kotlin/id/homebase/core/notifications/SystemNotificationSettings.native.kt` (the iOS `actual`):
```kotlin
1  package id.homebase.core.notifications
2
3  import androidx.compose.runtime.Composable
4
5  @Composable
6  actual fun rememberOpenSystemNotificationSettings(): () -> Unit {
7      // On iOS, notification settings are managed through iOS Settings app.
8      // KMPNotifier handles the permission prompt on initialization.
9      // A deeper integration would use UIApplication.openSettingsURLString.
10     return { /* TODO: Open iOS Settings via platform bridge if needed */ }
11 }
```

The `expect` (do NOT touch) — `homebase-common/src/commonMain/kotlin/id/homebase/core/notifications/SystemNotificationSettings.kt:11-12`:
```kotlin
@Composable
expect fun rememberOpenSystemNotificationSettings(): () -> Unit
```

Android exemplar (do NOT touch) — `homebase-common/src/androidMain/.../SystemNotificationSettings.android.kt:12-29` returns `remember(context) { { … startActivity(ACTION_APP_NOTIFICATION_SETTINGS) } }`.

Consumer (do NOT touch) — `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/notifications/NotificationSettingsScreen.kt:95` calls `val openSystemSettings = rememberOpenSystemNotificationSettings()`. Note: this screen also routes the `OpenSystemNotificationSettings` action through `permissionManager.launchSettings()` (line 116-117), but `openSystemSettings` is the value this plan repairs; leave the screen unchanged.

**Verified exemplar to copy from** — `homebase-common/src/nativeMain/kotlin/id/homebase/core/permissions/PermissionsManager.native.kt`. Imports at lines 21, 31, 32:
```kotlin
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
```
Body at lines 248-252 (this exact call compiles against the project's Kotlin/Native version):
```kotlin
override fun launchSettings() {
    NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
        UIApplication.sharedApplication.openURL(it, emptyMap<Any?, String>(), {} )
    }
}
```
This is the 3-arg `openURL(url, options:, completionHandler:)` overload — NOT the deprecated 1-arg `openURL(url)`. Use this overload.

### Finding B — missing URI-scheme guard in iOS fileExists
`homebase-chat/src/nativeMain/kotlin/id/homebase/chat/services/FileExistence.native.kt` (the iOS `actual`):
```kotlin
1  package id.homebase.chat.services
2
3  import platform.Foundation.NSFileManager
4
5  internal actual fun fileExists(path: String): Boolean =
6      NSFileManager.defaultManager.fileExistsAtPath(path)
```

The `expect` (do NOT touch) — `homebase-chat/src/commonMain/.../FileExistence.kt:3`:
```kotlin
internal expect fun fileExists(path: String): Boolean
```

Android exemplar (do NOT touch) — `homebase-chat/src/androidMain/.../FileExistence.android.kt:3-9`:
```kotlin
internal actual fun fileExists(path: String): Boolean {
    // Non-filesystem references (content://, file://, http(s)://, …) are
    // opaque from here — assume they're valid and let the consumer
    // resolve them. Filesystem paths fall through to the real check.
    if (path.contains("://")) return true
    return java.io.File(path).exists()
}
```
Desktop exemplar (do NOT touch) — `homebase-chat/src/jvmMain/.../FileExistence.jvm.kt:3-10` is byte-identical (same `if (path.contains("://")) return true` guard). Web — `FileExistence.web.kt:7` is `path.contains("://")`.

Consumer that breaks (do NOT touch) — `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/LocalVideoContextStore.kt`: `fileExists(...)` is the eviction predicate at lines 99, 108, 116, 121, 126. When it wrongly returns `false` for a scheme URL, the store drops the entry.

Contract test that documents the intended behaviour (do NOT touch in Step for B; you ONLY read it) — `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/FileExistenceTest.kt` asserts `content://`, `file://`, `https://`, `http://` all return `true` (lines 18-33) and that real filesystem paths fall through (lines 39-79).

**Convention:** KMP `expect`/`actual`; iOS-specific code lives in `src/nativeMain/`; the scheme-guard heuristic is the cross-platform contract every `actual` must uphold. Exemplar to match for B: `FileExistence.android.kt`.

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check | `git diff --stat 45e2832e..HEAD -- homebase-common/src/nativeMain/kotlin/id/homebase/core/notifications/SystemNotificationSettings.native.kt homebase-chat/src/nativeMain/kotlin/id/homebase/chat/services/FileExistence.native.kt` | no output (no drift); if output, reconcile vs excerpts first |
| Compile common on iOS (Finding A) | `./gradlew :homebase-common:compileKotlinIosSimulatorArm64` | `BUILD SUCCESSFUL` (macOS host only) |
| Compile chat on iOS (Finding B) | `./gradlew :homebase-chat:compileKotlinIosSimulatorArm64` | `BUILD SUCCESSFUL` (macOS host only) |
| jvm contract test (unchanged, must still pass) | `./gradlew :homebase-chat:jvmTest` | `BUILD SUCCESSFUL`; `FileExistenceTest` all green |
| Confirm no stray no-op TODO remains | `grep -n "TODO: Open iOS Settings" homebase-common/src/nativeMain/kotlin/id/homebase/core/notifications/SystemNotificationSettings.native.kt` | no output |
| Confirm iOS guard landed | `grep -n 'contains("://")' homebase-chat/src/nativeMain/kotlin/id/homebase/chat/services/FileExistence.native.kt` | one match |
| Scope check | `git status --porcelain` | only the two in-scope `.native.kt` files + `plans/` |

> Host note: `compileKotlinIosSimulatorArm64` requires a macOS host. This repo's checkout host is macOS (Darwin). If you are NOT on macOS, you CANNOT verify the iOS compile locally — make the edits, run the jvm test, and rely on CI (`.github/workflows/test.yml` iOS-simulator job). Record in your report that the iOS compile was not locally verified.

## Scope
In scope (the ONLY files you may modify):
- `homebase-common/src/nativeMain/kotlin/id/homebase/core/notifications/SystemNotificationSettings.native.kt` — implement Finding A.
- `homebase-chat/src/nativeMain/kotlin/id/homebase/chat/services/FileExistence.native.kt` — implement Finding B.
- `plans/README.md` — append/update this plan's row at the end (Step 4).

Out of scope (do NOT touch):
- `SystemNotificationSettings.kt` (commonMain expect) — signature is correct; changing it breaks every platform.
- `SystemNotificationSettings.android.kt` / `.jvm.kt` / `.web.kt` — already correct; these are the exemplars.
- `FileExistence.kt` (commonMain expect), `.android.kt`, `.jvm.kt`, `.web.kt` — already correct; the jvm/android versions define the contract.
- `NotificationSettingsScreen.kt` and its ViewModel — the wiring is fine; the bug is in the `actual`, not the call site.
- `LocalVideoContextStore.kt` — it correctly trusts `fileExists`; fixing `fileExists` fixes it.
- `FileExistenceTest.kt` — it is a jvm contract test and stays unchanged (the iOS `actual` is not exercised by jvmTest; see Test plan + Maintenance).

## Steps

### Step 1 — Finding B (smaller, isolates the simplest change first): add the scheme guard to iOS `fileExists`
Replace the body of `homebase-chat/src/nativeMain/kotlin/id/homebase/chat/services/FileExistence.native.kt` so it matches the Android/Desktop contract — short-circuit scheme URLs before the filesystem check. Final file:
```kotlin
package id.homebase.chat.services

import platform.Foundation.NSFileManager

internal actual fun fileExists(path: String): Boolean {
    // Non-filesystem references (content://, file://, http(s)://, …) are
    // opaque from here — assume they're valid and let the consumer
    // resolve them. Filesystem paths fall through to the real check.
    // Parity with FileExistence.android.kt / .jvm.kt; covered by jvmTest
    // FileExistenceTest (the shared contract).
    if (path.contains("://")) return true
    return NSFileManager.defaultManager.fileExistsAtPath(path)
}
```
Keep the `import platform.Foundation.NSFileManager` (still used). The function stays `internal actual fun` with the exact `(path: String): Boolean` signature from the `expect`.

Verify: `./gradlew :homebase-chat:compileKotlinIosSimulatorArm64` -> `BUILD SUCCESSFUL` (macOS only — if not on macOS, skip and note it).
Verify: `./gradlew :homebase-chat:jvmTest` -> `BUILD SUCCESSFUL` (the jvm actual is unchanged, so the contract test must still pass; this also proves you did not accidentally touch jvm/common).
Verify: `grep -n 'contains("://")' homebase-chat/src/nativeMain/kotlin/id/homebase/chat/services/FileExistence.native.kt` -> exactly one match.

### Step 2 — Finding A: implement the iOS notification-settings opener
Replace the whole of `homebase-common/src/nativeMain/kotlin/id/homebase/core/notifications/SystemNotificationSettings.native.kt` with an `actual` that returns a function opening iOS Settings, using the verified exemplar from `PermissionsManager.native.kt:248-252`. Final file:
```kotlin
package id.homebase.core.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

@Composable
actual fun rememberOpenSystemNotificationSettings(): () -> Unit {
    // Opens the app's page in iOS Settings.app, where the user manages
    // notification permissions (iOS has no per-app notification-settings
    // deep link distinct from the app settings page). Parity with the
    // Android actual (ACTION_APP_NOTIFICATION_SETTINGS) and the Desktop
    // actual (OS prefs). Mirrors PermissionsManager.native.kt#launchSettings.
    return remember {
        {
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                UIApplication.sharedApplication.openURL(it, emptyMap<Any?, String>(), {})
            }
            Unit
        }
    }
}
```
Notes for the executor:
- Keep it a `@Composable` returning `() -> Unit` exactly as the `expect` declares. Wrapping the returned lambda in `remember { }` matches the Android actual (`remember(context) { … }`) and keeps the closure stable across recompositions; no key is needed because the lambda captures nothing.
- The trailing `Unit` makes the lambda's type unambiguously `() -> Unit` (the `?.let { … }` expression is `Unit?`/`Any?`). If the compiler already infers `() -> Unit` cleanly without it, that's fine too — but keeping `Unit` is safe and explicit.
- If `import androidx.compose.runtime.remember` triggers an unused-import lint because you chose not to wrap, either keep the `remember` wrapper (preferred) or drop both the wrapper and the import together. Do not leave an unused import.
- `openURL(it, emptyMap<Any?, String>(), {})` is the 3-arg overload proven to compile in `PermissionsManager.native.kt:250`. If the symbol names somehow do not resolve against this Kotlin/Native version (they should — same module, same source set family), STOP and report the exact unresolved symbol rather than guessing an alternative.

Verify: `./gradlew :homebase-common:compileKotlinIosSimulatorArm64` -> `BUILD SUCCESSFUL` (macOS only — if not on macOS, skip and note it).
Verify: `grep -n "TODO: Open iOS Settings" homebase-common/src/nativeMain/kotlin/id/homebase/core/notifications/SystemNotificationSettings.native.kt` -> no output.

### Step 3 — full in-scope compile + scope sanity
Verify both modules still compile on iOS together (macOS only):
`./gradlew :homebase-common:compileKotlinIosSimulatorArm64 :homebase-chat:compileKotlinIosSimulatorArm64` -> `BUILD SUCCESSFUL`.
Verify scope: `git status --porcelain` -> only `homebase-common/.../SystemNotificationSettings.native.kt`, `homebase-chat/.../FileExistence.native.kt`, and `plans/` entries appear (plus pre-existing untracked xcuserstate/`.agents/`/`skills-lock.json` noise that predates this plan — do not stage those).

### Step 4 — update plans/README.md
If `plans/README.md` does not exist, create it with a table header, then add this plan's row. If it exists, append/update the row for plan 006. Row content:
`| 006 | Fix iOS notification-settings button + fileExists scheme guard | P1 | S | LOW | Done |`
(Use whatever column set the existing README already defines; match its format. If creating fresh, use: `| Plan | Title | Priority | Effort | Risk | Status |`.)

Verify: `grep -n "006" plans/README.md` -> the row is present.

## Test plan
No new automated test is added for the iOS `actual` directly, because `homebase-chat` has only `commonTest` and `jvmTest` source sets (verified: `find homebase-chat/src -maxdepth 1 -type d -name '*Test'`) — there is no `nativeTest`/`iosTest` harness, and standing one up is out of scope for this S/LOW change.

Coverage relied upon:
- The behaviour contract for Finding B is already encoded in `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/FileExistenceTest.kt` — `contentUri_isAssumedValid`, `fileUri_isAssumedValid`, `httpUrl_isAssumedValid` (scheme URLs → `true`) plus the filesystem-fallthrough cases. The iOS `actual` is now byte-equivalent in its scheme branch, so the same contract holds; the jvm test is the regression guard for the heuristic. Run: `./gradlew :homebase-chat:jvmTest` -> all green.
- Finding A has no behavioural unit (it opens an OS Settings page — side-effecting UIKit call with no return value); the gate is the iOS compile (`:homebase-common:compileKotlinIosSimulatorArm64`) plus manual device verification (see Done criteria optional manual check).

Model-after note: if a future executor adds a `nativeTest` source set, model an iOS `FileExistenceTest` on the existing jvm one (same four scheme assertions; the filesystem cases need an iOS-writable temp dir via `NSTemporaryDirectory()` instead of `File.createTempFile`).

## Done criteria
- `git diff --stat 45e2832e..HEAD -- <the two in-scope native files>` shows both files changed (drift check passed at start; now they are the intended diff).
- `grep -n "TODO: Open iOS Settings" homebase-common/src/nativeMain/.../SystemNotificationSettings.native.kt` -> no output.
- `grep -n 'contains("://")' homebase-chat/src/nativeMain/.../FileExistence.native.kt` -> exactly one match.
- `grep -n "openURL" homebase-common/src/nativeMain/.../SystemNotificationSettings.native.kt` -> one match (the opener is wired).
- `./gradlew :homebase-common:compileKotlinIosSimulatorArm64 :homebase-chat:compileKotlinIosSimulatorArm64` -> `BUILD SUCCESSFUL` (macOS host; if non-macOS, this criterion is deferred to CI — state so explicitly in the report).
- `./gradlew :homebase-chat:jvmTest` -> `BUILD SUCCESSFUL`, `FileExistenceTest` all pass (proves jvm contract unbroken).
- `git status --porcelain` -> only the two in-scope `.native.kt` files and `plans/` are modified/added (ignore the pre-existing untracked xcuserstate/`.agents/`/`skills-lock.json`).
- `plans/README.md` contains the 006 row.
- (Optional manual device check, if a Mac + iPhone/simulator is available) Settings → Notifications screen: tap the "System default sound" row → iOS Settings.app opens to the app page. And a downloaded local video keeps its context after a refresh (no false eviction).

## STOP conditions
- Drift: the drift-check diff shows either in-scope `.native.kt` file already changed and its content no longer matches the Current-state excerpt above (e.g. the TODO already replaced, or the guard already added). Reconcile, and if the fix is already present, STOP and report "already fixed" rather than re-applying.
- Any UIKit/Foundation symbol in Step 2 (`UIApplication`, `UIApplicationOpenSettingsURLString`, `NSURL.URLWithString`, the 3-arg `openURL`) fails to resolve on `:homebase-common:compileKotlinIosSimulatorArm64`. Do NOT swap in a guessed alternative API — STOP and report the exact unresolved symbol (they are proven to compile in `PermissionsManager.native.kt`, so a failure means something else drifted).
- Either iOS compile fails twice for an unrelated reason (pre-existing breakage in the module) — STOP; the breakage is not in your two-line change.
- The fix would require editing any out-of-scope file (the expect, another actual, the screen, the store, or the jvm test) to compile — STOP; that means an assumption here is wrong.
- Assumption "homebase-chat has no nativeTest source set" proves false (a `nativeTest`/`iosTest` dir exists) — STOP and reconsider adding a real iOS test instead of relying solely on the jvm contract.

## Maintenance notes
- The scheme-guard `if (path.contains("://")) return true` is now duplicated across four `actual`s (android/jvm/web/native). If the heuristic ever changes (e.g. to a stricter scheme allowlist), change all four and the jvmTest together. A future refactor could lift the guard into the `expect`'s common wrapper (a common `fun fileExists(path) = path.contains("://") || platformFileExists(path)`), leaving each platform to implement only the real filesystem check — that would delete the duplication and let `commonTest` cover the guard on every target. Deferred; out of scope here.
- The iOS `actual` for `fileExists` is currently untested by any automated suite (no `nativeTest` harness). A reviewer should scrutinize that the scheme branch is byte-equivalent to the android/jvm exemplar. If iOS test infra lands, port `FileExistenceTest` to `nativeTest` per the Test-plan note.
- For Finding A: iOS exposes no notification-settings-specific deep link; `UIApplicationOpenSettingsURLString` lands on the app's general Settings page (this is the same target Apple's own permission re-prompt flow uses, mirrored by `PermissionsManager.launchSettings()`). If Apple later ships a dedicated notification-settings URL, prefer it. A reviewer should confirm the returned lambda is stable (the `remember` wrapper) and that the screen still routes taps correctly (it does, via `openSystemSettings` / `launchSettings`).
- Watch for the deprecated 1-arg `UIApplication.openURL(url)` creeping in via copy-paste; always use the 3-arg `openURL(url, options:, completionHandler:)` overload as in `PermissionsManager.native.kt:250`.
