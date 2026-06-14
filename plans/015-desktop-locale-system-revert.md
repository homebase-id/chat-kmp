# Plan 015: Make Desktop "System" locale option actually revert to the OS locale

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-common/src/jvmMain/kotlin/id/homebase/core/settings/LocaleHelper.desktop.kt`. If that file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P3
- Effort: S
- Risk: LOW
- Depends on: none
- Category: bug (cross-platform parity)
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
On the Appearance settings screen the user can pick a language; one of the choices is "System" (the OS default locale). On Android, iOS, and Web that choice genuinely reverts the app to follow the OS locale (empty locale list / remove `AppleLanguages` / remove `app_locale` + reload). On **Desktop the "System" choice is a no-op that silently leaves the previous override in place** — so a user who picked Danish and then picks "System" stays on Danish. The Desktop branch reads `Locale.getDefault()` (which already includes any prior override) and assigns it straight back, so the original OS locale is never restored. The concrete cost is a broken, user-reachable setting on Desktop that contradicts the other three platforms. The fix captures the real OS locale once at process start and restores it for the "System" case, bringing Desktop to parity.

## Current state

### The broken file (the ONLY file you will modify)
`homebase-common/src/jvmMain/kotlin/id/homebase/core/settings/LocaleHelper.desktop.kt` — JVM/Desktop actual of the `expect` locale API. Full current contents (verified, no drift vs base):

```kotlin
1  package id.homebase.core.settings
2
3  import java.util.Locale
4
5  actual fun getSystemLocale(): String {
6      return Locale.getDefault().language
7  }
8
9  actual fun setPlatformSystemLocale(languageCode: String) {
10     val locale = when (languageCode) {
11         "system" -> Locale.getDefault()          // <-- BUG: returns the CURRENT default (incl. prior override)
12         else -> Locale.forLanguageTag(languageCode)
13     }
14     Locale.setDefault(locale)                    // <-- so this re-applies the override, never the OS locale
15 }
```

The `"system"` branch does `Locale.getDefault()` then `Locale.setDefault(it)`. Because a previous call to `setPlatformSystemLocale("da-DK")` already did `Locale.setDefault(Danish)`, `Locale.getDefault()` now returns Danish — so the "system" path is a self-assignment that re-applies the override instead of reverting.

### The expect declaration + flow (read-only, do NOT modify)
`homebase-common/src/commonMain/kotlin/id/homebase/core/settings/LocaleHelper.kt`:
```kotlin
6   expect fun getSystemLocale(): String
8   expect fun setPlatformSystemLocale(languageCode: String)
10  fun applyStoredLocale(userPreferences: UserPreferences) {
11      val savedLanguage = userPreferences.language
12      setPlatformSystemLocale(savedLanguage)
13  }
19  enum class Language(val code: String) {
20      SYSTEM("system"),
21      ENGLISH_US("en-US"),
22      ENGLISH_GB("en-GB"),
23      DANISH("da-DK");
```
Note the sentinel string is the literal `"system"` (matching `Language.SYSTEM.code`).

### The user-reachable call site (read-only, do NOT modify)
`homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/appearance/AppearanceSettingsScreen.kt:64`:
```kotlin
60  LaunchedEffect(uiState.uiEvent) {
61      when (val event = uiState.uiEvent) {
62          is AppearanceSettingsUiEvent.SetLanguage -> {
63              viewModel.eventConsumed()
64              setPlatformSystemLocale(event.language)
```
This runs on all platforms, so the broken Desktop "System" path is genuinely reachable from the UI.

### How Desktop applies the stored locale at startup (read-only, do NOT modify)
`desktopApp/src/jvmMain/kotlin/id/homebase/app/Main.kt:147`:
```kotlin
147  runBlocking { applyStoredLocale(userPreferences) }
```
This is invoked once during startup, so by the time any user override is applied the JVM default still equals the OS locale — making a class-load-time capture (Step 1 below) reliably the OS locale in production. (`Main.kt` does not touch `Locale` before this line; assume nothing earlier mutates `Locale.getDefault()`.)

### The three platforms that already work (read-only — these are the parity target, do NOT modify)
- Android `LocaleHelper.android.kt:18` → `LocaleListCompat.getEmptyLocaleList()` (reverts to OS).
- iOS `LocaleHelper.native.kt:14-15` → `defaults.removeObjectForKey("AppleLanguages")` (reverts to OS).
- Web `LocaleHelper.web.kt:9-12` → `localStorage.removeItem('app_locale'); window.location.reload();` (reverts to OS).

### Convention this fix follows
KMP `expect`/`actual`; only the JVM actual changes. Capture-OS-locale-once is the JVM analogue of "empty locale list" / "remove key". The existing JVM test convention (FAKES, kotlin.test, no Mockito/MockK) and a directly-applicable exemplar live at
`homebase-common/src/jvmTest/kotlin/id/homebase/core/util/ByteArrayExtensionsTest.kt` (plain `@Test` + `assertEquals`/`assertContentEquals`) — model the new test on it.

### A test hazard to know about
`homebase-common/src/jvmMain/kotlin/id/homebase/core/test/TestHelpers.jvm.kt:6` does `Locale.setDefault(Locale.forLanguageTag(languageTag))`, and other JVM tests mutate the JVM default locale. The "original OS locale" therefore MUST be captured as a top-level `val` initialized at **class/file load**, before any test (or any user override) runs — NOT read lazily inside `setPlatformSystemLocale`. The test in this plan saves and restores `Locale.getDefault()` around itself so it doesn't poison sibling tests.

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check on target file | `git diff --stat 45e2832e..HEAD -- homebase-common/src/jvmMain/kotlin/id/homebase/core/settings/LocaleHelper.desktop.kt` | empty output (no drift) |
| Compile the JVM actual (primary gate) | `./gradlew :homebase-common:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Run the new + existing JVM unit tests | `./gradlew :homebase-common:jvmTest` | `BUILD SUCCESSFUL`, new test class passes |
| Run only the new test class (faster iteration) | `./gradlew :homebase-common:jvmTest --tests "id.homebase.core.settings.DesktopLocaleHelperTest"` | `BUILD SUCCESSFUL` |
| Confirm only in-scope files changed | `git status --porcelain` | only the two paths in Scope (plus this plan / README) |
| Guard against the regression returning | `grep -n '"system" -> Locale.getDefault()' homebase-common/src/jvmMain/kotlin/id/homebase/core/settings/LocaleHelper.desktop.kt` | no output (nonzero exit) |

## Scope
**In scope (modify / create):**
- `homebase-common/src/jvmMain/kotlin/id/homebase/core/settings/LocaleHelper.desktop.kt` — fix the `"system"` branch + add the captured-original `val`.
- `homebase-common/src/jvmTest/kotlin/id/homebase/core/settings/DesktopLocaleHelperTest.kt` — NEW regression test (create).
- `plans/README.md` — add/update this plan's row (create the file if it does not exist).

**Out of scope (do NOT touch):**
- `LocaleHelper.android.kt` / `LocaleHelper.native.kt` / `LocaleHelper.web.kt` — already correctly revert; no change needed.
- `LocaleHelper.kt` (commonMain expect + `Language` enum) — the contract and the `"system"` sentinel are correct; don't change the API.
- `AppearanceSettingsScreen.kt` / `AppearanceSettingsViewModel.kt` — call site is correct; the bug is entirely in the JVM actual.
- `desktopApp/.../Main.kt` — startup ordering already works for the capture approach.
- `TestHelpers.jvm.kt` / `DateTimeFormatter.jvm.kt` — unrelated `Locale` users.

## Steps

### Step 1 — Run the drift check
Run: `git diff --stat 45e2832e..HEAD -- homebase-common/src/jvmMain/kotlin/id/homebase/core/settings/LocaleHelper.desktop.kt`
- If output is empty: proceed.
- If non-empty: open the file and compare against the Current state excerpt. If the `"system" -> Locale.getDefault()` line is gone or already fixed, STOP and report "already fixed / drifted".

Verify: command output is empty → proceed.

### Step 2 — Fix the JVM actual
Edit `homebase-common/src/jvmMain/kotlin/id/homebase/core/settings/LocaleHelper.desktop.kt` to capture the OS locale once at file load and restore it for the `"system"` case. Replace the entire file body with:

```kotlin
package id.homebase.core.settings

import java.util.Locale

/**
 * The JVM default locale captured ONCE at class load, before any user-selected
 * language override is applied via [setPlatformSystemLocale]. On Desktop this is
 * the OS locale, because the app only overrides the locale later (see
 * desktopApp .../Main.kt -> applyStoredLocale). Selecting "system" must restore
 * THIS value, not the current (possibly overridden) Locale.getDefault().
 */
private val originalOsLocale: Locale = Locale.getDefault()

actual fun getSystemLocale(): String {
    return Locale.getDefault().language
}

actual fun setPlatformSystemLocale(languageCode: String) {
    val locale = when (languageCode) {
        Language.SYSTEM.code -> originalOsLocale
        else -> Locale.forLanguageTag(languageCode)
    }
    Locale.setDefault(locale)
}
```

Notes for the executor:
- Use `Language.SYSTEM.code` (resolves to `"system"`, same package) instead of the bare string literal — it ties the branch to the enum and removes the magic string. The hard-coded `"system"` literal must NOT remain.
- Do NOT read `originalOsLocale` lazily inside the function — it must be a top-level `val` so it is initialized at class load, before any override.
- Do NOT call `Locale.setDefault` anywhere except the existing single call site.

Verify: `./gradlew :homebase-common:compileKotlinJvm` → `BUILD SUCCESSFUL`.
Verify: `grep -n '"system" -> Locale.getDefault()' homebase-common/src/jvmMain/kotlin/id/homebase/core/settings/LocaleHelper.desktop.kt` → no output (the regression string is gone).

### Step 3 — Add the regression test
Create `homebase-common/src/jvmTest/kotlin/id/homebase/core/settings/DesktopLocaleHelperTest.kt` with:

```kotlin
package id.homebase.core.settings

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for plan 015: the Desktop "system" locale option used to read
 * Locale.getDefault() (the already-overridden value) and re-apply it, so it
 * never reverted to the OS locale. It must now restore the locale captured at
 * class load (the original OS locale in production).
 *
 * NOTE: originalOsLocale in LocaleHelper.desktop.kt is captured at class load.
 * In the test JVM the default at that moment is whatever the test runner started
 * with; this test asserts the "system" branch returns to THAT captured baseline
 * after an explicit override, which is exactly the production guarantee.
 */
class DesktopLocaleHelperTest {

    // Save the JVM default so this test does not poison sibling tests that read
    // Locale.getDefault() (e.g. DateTimeFormatter tests).
    private val savedDefault: Locale = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(savedDefault)
    }

    @Test
    fun system_reverts_to_captured_baseline_after_override() {
        // Baseline captured by the helper at class load.
        val baseline = Locale.getDefault()

        // Apply an explicit, distinctly different override.
        setPlatformSystemLocale("da-DK")
        assertEquals("da", Locale.getDefault().language)

        // Selecting "system" must restore the captured baseline, NOT keep Danish.
        setPlatformSystemLocale(Language.SYSTEM.code)
        assertEquals(baseline.language, Locale.getDefault().language)
        assertEquals(baseline.country, Locale.getDefault().country)
    }

    @Test
    fun explicit_language_tag_is_applied() {
        setPlatformSystemLocale("en-GB")
        assertEquals("en", Locale.getDefault().language)
        assertEquals("GB", Locale.getDefault().country)
    }
}
```

Important subtlety for the executor: `originalOsLocale` is captured when the helper class first loads, which (because both run in the same test JVM) equals the default at test-class load time. The test above does NOT hard-code an expected baseline — it reads `Locale.getDefault()` as `baseline` at the start of the test and asserts the `"system"` branch returns to it. This is robust regardless of what locale the CI test JVM starts in. The pre-fix code would FAIL `system_reverts_to_captured_baseline_after_override` because after the `da-DK` override, the old `"system"` branch returned `Locale.getDefault()` (= Danish) and the final assert (`baseline.language` likely `en`) would not equal `da`.

Verify: `./gradlew :homebase-common:jvmTest --tests "id.homebase.core.settings.DesktopLocaleHelperTest"` → `BUILD SUCCESSFUL`, 2 tests passed.

### Step 4 — Full module test sweep
Run the whole module test suite to confirm the `@AfterTest` restore prevents locale leakage into other JVM tests.

Verify: `./gradlew :homebase-common:jvmTest` → `BUILD SUCCESSFUL` (no other test regresses).

### Step 5 — Update plans/README.md
If `plans/README.md` does not exist, create it with a header and a table; otherwise add/update the row for plan 015. Use this row format:

```
| 015 | Desktop "System" locale reverts to OS locale | bug/parity | P3 | done |
```

If creating the file, use:
```markdown
# Implementation plans

| Plan | Title | Category | Priority | Status |
|------|-------|----------|----------|--------|
| 015 | Desktop "System" locale reverts to OS locale | bug/parity | P3 | done |
```

Verify: `grep -n "015" plans/README.md` → shows the row.

## Test plan
- New file: `homebase-common/src/jvmTest/kotlin/id/homebase/core/settings/DesktopLocaleHelperTest.kt`.
- Cases:
  1. `system_reverts_to_captured_baseline_after_override` — the regression this plan fixes: override to `da-DK`, then `"system"`, assert default returns to the captured baseline (NOT Danish). Fails on the pre-fix code.
  2. `explicit_language_tag_is_applied` — sanity: `en-GB` sets language `en` / country `GB` (the non-system branch still works).
- Model after: `homebase-common/src/jvmTest/kotlin/id/homebase/core/util/ByteArrayExtensionsTest.kt` (plain `@Test` + `assertEquals`, no mocks).
- Each test saves/restores `Locale.getDefault()` via `@AfterTest` so it cannot poison `DateTimeFormatter` tests in the same module.
- Verify command: `./gradlew :homebase-common:jvmTest` → `BUILD SUCCESSFUL`.

## Done criteria
- [ ] `./gradlew :homebase-common:compileKotlinJvm` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-common:jvmTest` → `BUILD SUCCESSFUL`, 2 new tests in `DesktopLocaleHelperTest` pass.
- [ ] `grep -n '"system" -> Locale.getDefault()' homebase-common/src/jvmMain/kotlin/id/homebase/core/settings/LocaleHelper.desktop.kt` → no output (regression string removed).
- [ ] `grep -n 'originalOsLocale' homebase-common/src/jvmMain/kotlin/id/homebase/core/settings/LocaleHelper.desktop.kt` → shows the top-level `val` and its use in the `"system"` branch.
- [ ] `git status --porcelain` → only `LocaleHelper.desktop.kt`, the new test file, `plans/015-...md`, and `plans/README.md` are listed (no Android/iOS/web/common/core files changed).
- [ ] plans/README.md has the plan 015 row marked `done`.

## STOP conditions
- Drift: Step 1 shows the target file changed AND the `"system" -> Locale.getDefault()` line is no longer present (already fixed) — STOP, report.
- Any verification command fails twice in a row after an honest fix attempt — STOP, report the exact failure.
- The fix appears to require editing a file outside Scope (e.g. you feel you must change `AppearanceSettingsScreen.kt`, `Main.kt`, or any other actual) — STOP, because that means an assumption here is wrong; report what you found.
- Assumption that proves false: if you discover `desktopApp/.../Main.kt` (or anything in the Desktop startup path) sets a non-OS `Locale.setDefault(...)` BEFORE `LocaleHelper.desktop.kt` is first class-loaded, then `originalOsLocale` would capture the override instead of the OS locale — STOP and report; the capture point would need to move earlier (e.g. into `Main` before any override) rather than this file. (At base commit, `Main.kt:147` only calls `applyStoredLocale`, so this is not expected.)

## Maintenance notes
- A reviewer should confirm `originalOsLocale` is a **top-level `val` initialized at class load** (eager), not a function-local read — that property is what makes "system" revert correctly. If a future refactor makes the helper a class or moves it behind a lazy initializer, re-verify the capture still happens before any override.
- Future change that interacts: anything that calls `Locale.setDefault` early in Desktop startup (before the chat UI loads) could pre-poison the captured baseline. Keep `Locale` mutation out of the pre-`applyStoredLocale` startup path, or move the capture to a guaranteed-first point.
- Deferred follow-up (NOT in this plan): on Desktop, a locale change still needs a window/app recreate to fully repaint already-composed strings (same caveat the iOS actual notes with "app restart is required"). Triggering a Compose window recreation on locale change is a separate, larger change; track it independently rather than bolting it onto this revert fix.
- Web/Android/iOS actuals were intentionally left untouched; they already revert. Do not "unify" them into a shared helper as part of this fix — their revert mechanisms are platform-specific (locale list vs NSUserDefaults vs localStorage) and don't share a JVM `Locale`.
