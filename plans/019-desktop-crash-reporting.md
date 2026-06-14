# Plan 019: Wire remote crash/error reporting for Desktop (currently mobile-only)

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-common/src/jvmMain/kotlin/id/homebase/core/logging desktopApp/src/jvmMain/kotlin/id/homebase/app/Main.kt gradle/libs.versions.toml homebase-common/build.gradle.kts`. On mismatch with the Current state excerpts, STOP.

## Status
- Priority: **P3**
- Effort: **M**
- Risk: **LOW**
- Depends on: none
- Category: **migration** (parity / observability)
- Planned at: commit 45e2832e, 2026-06-14

> **Drift note:** At planning time `git rev-parse --short HEAD` == `45e2832e` (the base commit), so no drift was possible and `codeMatchedFinding=true`. All line numbers below were read directly at this commit. If the Drift-check diff above prints any line for these files, re-open each cited file and re-confirm the excerpts before proceeding.

> **DECISION POINT — this plan does NOT proceed past Step 2 without operator confirmation.** It adds either a new SaaS dependency (Option A) or a new network egress + endpoint (Option B). Both are product/privacy decisions. **Step 2 is an explicit STOP: you must get the operator to choose A or B and (for B) name the ingest endpoint + auth, before writing any backend code.** Default recommendation in this plan is **Option B** (no new SaaS); Option A is the alternative.

## Why this matters

On Android and iOS, handled exceptions and fatal crashes reach a remote dashboard (Firebase Crashlytics) so the team sees Desktop-class regressions without a user mailing a log file. On Desktop the same `expect`/`actual` seam exists but the JVM `actual`s are empty no-ops: `crashlyticsLog`/`crashlyticsRecordException` do nothing (`CrashReporter.jvm.kt:3-9`) and `setErrorCollectionEnabled` is unimplemented (`ErrorCollectionHandler.jvm.kt:3-5`). Desktop crashes only land in the on-disk `homebase.log` via `CrashLogger.logCrash`, which never leaves the machine. The concrete cost: a Desktop crash or a flood of handled exceptions is invisible to the team until a user voluntarily exports their log — so Desktop regressions ship and persist undetected. Closing this gives Desktop the same observability the mobile apps already have, while honoring the user's existing `errorCollectionEnabled` toggle and sending no PII.

## Current state

### The `expect` seam (DO NOT CHANGE — out of scope)
`homebase-common/src/commonMain/kotlin/id/homebase/core/logging/CrashReporter.kt` declares the API and the Kermit writer that drives it:

```kotlin
// CrashReporter.kt:16
expect fun crashlyticsLog(message: String)
// CrashReporter.kt:23
expect fun crashlyticsRecordException(throwable: Throwable)
// CrashReporter.kt:41-53  CrashlyticsLogWriter:
//   - every log >= Severity.Info  -> crashlyticsLog("X/tag: msg")   (breadcrumb)
//   - every log >= Severity.Error WITH a throwable -> crashlyticsRecordException(throwable)
```

`homebase-common/src/commonMain/kotlin/id/homebase/core/logging/ErrorCollectionHandler.kt:3`:
```kotlin
expect fun setErrorCollectionEnabled(enabled: Boolean)
```

### The JVM `actual`s you will implement (in scope)
`homebase-common/src/jvmMain/kotlin/id/homebase/core/logging/CrashReporter.jvm.kt` (whole file):
```kotlin
package id.homebase.core.logging

actual fun crashlyticsLog(message: String) {
    // No-op on Desktop — no crash-reporting backend is wired here yet.
}

actual fun crashlyticsRecordException(throwable: Throwable) {
    // No-op on Desktop — no crash-reporting backend is wired here yet.
}
```

`homebase-common/src/jvmMain/kotlin/id/homebase/core/logging/ErrorCollectionHandler.jvm.kt` (whole file):
```kotlin
package id.homebase.core.logging

actual fun setErrorCollectionEnabled(enabled: Boolean) {
    // not yet implemented
}
```

### Reference actuals (the contract to mirror — DO NOT CHANGE)
Android `CrashReporter.android.kt:5-11` forwards to `FirebaseCrashlytics.getInstance().log()` / `.recordException()`; `ErrorCollectionHandler.android.kt:5-7` sets `isCrashlyticsCollectionEnabled = enabled`. iOS `CrashReporter.native.kt:5-17` forwards through a native bridge. Note the contract that the JVM backend must preserve: `crashlyticsLog` is the breadcrumb (cheap, frequent, Info+), `crashlyticsRecordException` is the non-fatal record (Error+ with a throwable). The Android SDK itself gates upload on its collection flag; on JVM **you** own that gate — see Step 4.

### Where the writer is wired (already correct, DO NOT CHANGE)
`homebase-common/src/commonMain/kotlin/id/homebase/core/logging/LoggerConfig.kt:46` adds `CrashlyticsLogWriter()` to every platform's log writers, including Desktop. So once the JVM `actual`s do real work, Desktop logs already flow through them — no extra call-site wiring is needed for breadcrumbs/non-fatals.

### Fatal path on Desktop (in scope to extend)
`desktopApp/src/jvmMain/kotlin/id/homebase/app/Main.kt`:
- `main()` calls `setupCrashHandler()` at line 76, before Koin starts (`startKoin` at line 95).
- `setupCrashHandler()` (lines 269-282) installs a `Thread.setDefaultUncaughtExceptionHandler` that calls `CrashLogger.logCrash(thread.name, throwable)` (line 274) then chains the default handler. **This is the only place a Desktop fatal is observed; it currently writes text to `homebase.log` and nothing else.**
- `CrashLogger.logCrash` (`homebase-common/.../logging/CrashLogger.kt:11-31`) deliberately logs the fatal stack **as text, not via the throwable parameter**, so `CrashlyticsLogWriter` does NOT also record it as a non-fatal (comment at CrashLogger.kt:17-19). Keep that property: the fatal must be sent through a dedicated path, not by handing the throwable to Kermit.

### The user toggle (already wired through the common seam)
`UserPreferences.errorCollectionEnabled` (`homebase-common/.../settings/UserPreferences.kt:63-65`) defaults to **`true`**. `HelpViewModel.onAction(ToggleErrorCollection)` flips the pref and calls `setErrorCollectionEnabled(isEnabled)` (`homebase-core/.../help/HelpViewModel.kt:84-92`). On iOS, `MainViewController.kt:82` also calls `setErrorCollectionEnabled(UserPreferencesHelper.errorCollectionEnabled)` at startup to apply the stored value. **Desktop `Main.kt` has no equivalent startup apply call — Step 5 adds it.**

### Convention to follow
- KMP `expect`/`actual`: implement only the JVM `actual` body; never touch the `expect` or other `actual`s.
- Tests use **FAKES, not mocks** (no MockK/Mockito). `ktor-client-mock` (`MockEngine`) is the canonical fake HTTP transport — it is in the version catalog (`gradle/libs.versions.toml:145`) but NOT yet on `homebase-common`'s `jvmTest` (Step 6 adds it). Exemplar test to model structure/JVM-temp-dir/`@AfterTest` after: `homebase-common/src/jvmTest/kotlin/id/homebase/core/logging/LoggerConfigTest.kt`.
- No user-facing strings are added by this plan (so the Konsist `ArchitectureTest` is not a concern), but if you add any UI later, route every literal through `stringResource`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run first) | `git diff --stat 45e2832e..HEAD -- homebase-common/src/jvmMain/kotlin/id/homebase/core/logging desktopApp/src/jvmMain/kotlin/id/homebase/app/Main.kt gradle/libs.versions.toml homebase-common/build.gradle.kts` | No output (clean) — else STOP |
| Compile common (JVM) — primary gate | `./gradlew :homebase-common:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile desktop app (JVM) | `./gradlew :desktopApp:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Run the new + existing logging tests | `./gradlew :homebase-common:jvmTest --tests "id.homebase.core.logging.*"` | `BUILD SUCCESSFUL`, new test green |
| Verify no other platform actual changed | `git diff --stat -- homebase-common/src/androidMain homebase-common/src/nativeMain homebase-common/src/wasmJsMain` | No output |
| Confirm seam unchanged | `git diff -- homebase-common/src/commonMain/kotlin/id/homebase/core/logging/CrashReporter.kt homebase-common/src/commonMain/kotlin/id/homebase/core/logging/ErrorCollectionHandler.kt` | No output |

## Scope

**In scope (only these files may be modified/created):**
- `homebase-common/src/jvmMain/kotlin/id/homebase/core/logging/CrashReporter.jvm.kt` — implement the two `actual`s to forward to the chosen backend.
- `homebase-common/src/jvmMain/kotlin/id/homebase/core/logging/ErrorCollectionHandler.jvm.kt` — implement the `actual` to flip the gate that `CrashReporter.jvm.kt` reads.
- NEW `homebase-common/src/jvmMain/kotlin/id/homebase/core/logging/DesktopCrashTransport.kt` (Option B) — the seam-internal transport interface + default implementation, so the test can substitute a fake. (Option A: this file is the Sentry init wrapper instead.)
- `desktopApp/src/jvmMain/kotlin/id/homebase/app/Main.kt` — (a) apply the stored `errorCollectionEnabled` at startup; (b) Option A only: init the Sentry SDK in `main()`; (c) route the fatal in `setupCrashHandler()` to the remote path.
- `gradle/libs.versions.toml` — **only if Option A** (add Sentry) is chosen. Option B adds no catalog entry (ktor is already a `jvmMain` dep).
- `homebase-common/build.gradle.kts` — add `ktor-client-mock` to the `jvmTest` dependencies block (Step 6). Option A only: also add the Sentry lib to `jvmMain`.
- NEW `homebase-common/src/jvmTest/kotlin/id/homebase/core/logging/DesktopCrashReporterTest.kt` — the regression test.

**Out of scope (do NOT touch — why):**
- `CrashReporter.android.kt`, `ErrorCollectionHandler.android.kt`, `CrashReporter.native.kt`, `IOSCrashHandler.kt`, the wasmJs actuals — other platforms already work; the finding is JVM-only.
- `CrashReporter.kt`, `ErrorCollectionHandler.kt`, `CrashlyticsLogWriter`, `LoggerConfig.kt` — the common logging API shape is fixed; changing it would ripple across all platforms.
- `CrashLogger.kt` — its "log fatal as text, not throwable" property is load-bearing (prevents double-reporting); keep it.
- `HelpViewModel.kt`, `UserPreferences.kt` — the toggle already calls into the seam; no change needed.

## Steps

### Step 1 — Drift check and baseline build
Run the Drift-check command from the header. Then establish the current build is green so later failures are attributable to your change.

Verify: `git diff --stat 45e2832e..HEAD -- <in-scope paths>` → **no output**, and `./gradlew :homebase-common:compileKotlinJvm :desktopApp:compileKotlinJvm` → **BUILD SUCCESSFUL**. If the diff is non-empty, STOP (drift).

### Step 2 — DECISION POINT: confirm backend with the operator (STOP)
Present both options and get an explicit choice before writing any backend code. Do not pick on the user's behalf.

- **Option B (DEFAULT, low-dependency, recommended):** On next launch (and on the fatal path), POST a redacted crash/non-fatal payload + a stable, locally-generated install id to an **existing Homebase ingest endpoint** using the ktor CIO client already on `jvmMain`. No new SaaS, no new catalog entry. Requires the operator to name: (1) the ingest URL, (2) the auth scheme (header/token type — reference by file:line + credential type, never paste a secret), (3) the accepted payload shape.
- **Option A (alternative):** Add the **Sentry JVM SDK** to the catalog + `jvmMain`, `Sentry.init { dsn = ... }` in `Main.main()`, forward `crashlyticsLog`→`Sentry.addBreadcrumb`, `crashlyticsRecordException`→`Sentry.captureException`, and honor the gate via `options.isEnabled`/not-init. Requires the operator to provision a DSN (reference by config key, never paste it).

**STOP and report:** "Backend choice needed: A (Sentry, new SaaS + DSN) or B (POST to existing Homebase ingest — need URL + auth + payload shape). Defaulting to B if unspecified." Wait for the answer. If the operator does not respond, do **not** invent an endpoint or a DSN — stop here; the rest of the plan is blocked.

Verify: operator has chosen A or B and supplied the required config references. → proceed.

### Step 3 — Define the transport seam so the test can fake it (no behavior yet)
Create `homebase-common/src/jvmMain/kotlin/id/homebase/core/logging/DesktopCrashTransport.kt`.

**Option B:**
```kotlin
package id.homebase.core.logging

/**
 * Seam the JVM crash actuals send through, so jvmTest can substitute a fake
 * transport (we use FAKES, not mocks). The real impl POSTs to the Homebase
 * ingest endpoint via the ktor CIO client already on jvmMain.
 */
internal interface DesktopCrashTransport {
    /** Queue a breadcrumb line (cheap, frequent). */
    fun breadcrumb(line: String)
    /** Queue a non-fatal record (name + reason + redacted stack). */
    fun record(name: String, reason: String, redactedStack: String)
}

/** Backing field the actuals read; default no-op until installed. Swapped by tests. */
internal var desktopCrashTransport: DesktopCrashTransport = NoopDesktopCrashTransport

internal object NoopDesktopCrashTransport : DesktopCrashTransport {
    override fun breadcrumb(line: String) {}
    override fun record(name: String, reason: String, redactedStack: String) {}
}

/** Single source of truth for the gate. Defaults true to match the pref default. */
@Volatile internal var desktopErrorCollectionEnabled: Boolean = true
```
Do NOT yet write the real ktor-backed `DesktopCrashTransport` impl with the URL — that comes in Step 7 once the endpoint is confirmed. This step only introduces the seam + gate flag so the actuals (Step 4) and the test (Step 6) can compile.

**Option A:** instead define a thin `internal object SentryGate { @Volatile var enabled = true }` and keep `Sentry.*` calls direct in the actuals; you still need the `@Volatile internal var desktopErrorCollectionEnabled` flag pattern so the gate is unit-testable without a live Sentry transport. Model the file the same way (no DSN in source).

Verify: `./gradlew :homebase-common:compileKotlinJvm` → **BUILD SUCCESSFUL** (file compiles; nothing references it yet beyond its own declarations).

### Step 4 — Implement the JVM `actual`s behind the gate
Rewrite `CrashReporter.jvm.kt` so both actuals honor `desktopErrorCollectionEnabled` and forward to the transport. **Redact before sending** — reuse a redaction helper if the operator points you at one; otherwise inline a conservative scrub (strip anything matching a token/credential shape and never include `throwable.message` verbatim if it could carry user text — prefer class + stack frames). Keep the breadcrumb cheap.

**Option B shape:**
```kotlin
package id.homebase.core.logging

actual fun crashlyticsLog(message: String) {
    if (!desktopErrorCollectionEnabled) return
    desktopCrashTransport.breadcrumb(message)
}

actual fun crashlyticsRecordException(throwable: Throwable) {
    if (!desktopErrorCollectionEnabled) return
    val name = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "JvmException"
    val reason = throwable.message ?: throwable.toString()
    desktopCrashTransport.record(name, reason, redactStack(throwable))
}
```
Add a private `redactStack(Throwable): String` (or call the operator-provided redactor). Match the iOS cap idiom (`FATAL_BREADCRUMB_STACK_LIMIT = 30` in `CrashReporter.native.kt:20`) so a deep stack can't bloat the payload.

Then rewrite `ErrorCollectionHandler.jvm.kt`:
```kotlin
package id.homebase.core.logging

actual fun setErrorCollectionEnabled(enabled: Boolean) {
    desktopErrorCollectionEnabled = enabled
}
```

**Option A shape:** actuals check `SentryGate.enabled && Sentry.isEnabled()` then call `Sentry.addBreadcrumb(message)` / `Sentry.captureException(throwable)`; `setErrorCollectionEnabled` sets `SentryGate.enabled` and, when disabling, also stops capture (do not just rely on init).

Verify: `./gradlew :homebase-common:compileKotlinJvm` → **BUILD SUCCESSFUL**. The breadcrumb/non-fatal path is now live through `CrashlyticsLogWriter` (LoggerConfig.kt:46) on Desktop — no call-site wiring needed.

### Step 5 — Apply the stored toggle at Desktop startup (Main.kt)
Mirror iOS (`MainViewController.kt:82`). In `desktopApp/.../Main.kt`, **after** Koin is built and `UserPreferences` is resolved (the `val userPreferences = koin.get<UserPreferences>()` already exists at line 145), add:
```kotlin
setErrorCollectionEnabled(userPreferences.errorCollectionEnabled)
```
with `import id.homebase.core.logging.setErrorCollectionEnabled`. Place it near the existing `applyStoredLocale(userPreferences)` call (line 147) so stored prefs are applied together. **Order matters:** this must run before any non-fatal could be recorded with the wrong default; it is fine that breadcrumbs before this line use the `true` default since that matches the pref default.

Verify: `./gradlew :desktopApp:compileKotlinJvm` → **BUILD SUCCESSFUL**. Manually confirm the import is present and the call sits after `userPreferences` is resolved.

### Step 6 — Add the fake-transport test dependency and write the regression test
Add `ktor-client-mock` to `homebase-common/build.gradle.kts` `jvmTest.dependencies` block (currently lines 120-124 — compose.desktop, sqldelight, konsist):
```kotlin
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.konsist)
            implementation(libs.ktor.client.mock)   // fake HTTP transport for Desktop crash reporter
        }
```
(Option A needs no test dep — the gate is tested without a transport.)

Create `homebase-common/src/jvmTest/kotlin/id/homebase/core/logging/DesktopCrashReporterTest.kt`. Model the structure (JVM-only test, `@AfterTest` cleanup of shared singleton state) after `LoggerConfigTest.kt`. **Install a fake `DesktopCrashTransport` that records calls into lists**, exercise the actuals, and restore the no-op transport in `@AfterTest`.

Cases (the regression this fixes is the gate suppression):
1. `setErrorCollectionEnabled(false)` then `crashlyticsRecordException(RuntimeException("boom"))` → fake transport `record` list is **empty** (suppressed). This is the core regression: the no-op `setErrorCollectionEnabled` previously could not suppress anything.
2. `setErrorCollectionEnabled(false)` then `crashlyticsLog("x")` → fake `breadcrumb` list **empty**.
3. `setErrorCollectionEnabled(true)` then `crashlyticsRecordException(...)` → fake `record` called **once**; name == the throwable's qualified class, redacted stack is non-empty and contains **no** known secret-shaped substring (assert your `redactStack` removed a planted token, e.g. feed it a throwable whose message embeds a fake `Bearer abc123`-shaped string and assert it is absent).
4. (Option B) restore `desktopErrorCollectionEnabled = true` and `desktopCrashTransport = NoopDesktopCrashTransport` in `@AfterTest` so the shared `internal var`s don't leak into other tests in the same JVM (same single-JVM caveat called out in LoggerConfigTest.kt:22-25).

Because the test lives in the same `id.homebase.core.logging` package as the `internal` seam, it can read/write `desktopCrashTransport`, `desktopErrorCollectionEnabled`, and call the actuals directly. Use `MockEngine`/`ktor-client-mock` only if your fake transport is itself the ktor client (Option B real impl from Step 7); the simplest test fakes the `DesktopCrashTransport` interface and does not need a MockEngine — in that case you may skip the `ktor-client-mock` test dep. **Decide based on what your fake substitutes:** fake the interface (no MockEngine needed) is preferred and simplest.

Verify: `./gradlew :homebase-common:jvmTest --tests "id.homebase.core.logging.*"` → **BUILD SUCCESSFUL**, all four cases green, and `LoggerConfigTest` still green (no regression).

### Step 7 — (Option B only) Implement the real ktor transport and fatal wiring
Now that the operator confirmed the endpoint in Step 2, implement the real `DesktopCrashTransport` (the ktor CIO POST) in `DesktopCrashTransport.kt`: a stable install id persisted via `UserPreferences` (generate a random UUID once, store under a new key like `crash_install_id`; do NOT reuse `deviceId`/account identifiers), queue-and-flush on a background dispatcher, fire-and-forget (never block the EDT or the crash path). Reference the auth credential by config key/file:line only. Then in `Main.kt`'s `setupCrashHandler()` (lines 269-282), **after** `CrashLogger.logCrash(...)` (line 274) and **before** chaining the default handler, send the fatal through the remote path (gated by `desktopErrorCollectionEnabled`) as a synchronous best-effort flush — accept that a hard crash may lose it, exactly like iOS documents at `CrashReporter.native.kt:24-34`. Keep `CrashLogger.logCrash` logging the stack as text (do not hand the throwable to Kermit) so it isn't double-counted.

If Option A was chosen, this step is instead: `Sentry.init` in `main()` (early, before `setupCrashHandler`) reading the DSN from config (not source), and let Sentry's own uncaught handler + your `setErrorCollectionEnabled` gate cover the fatal.

Verify: `./gradlew :homebase-common:compileKotlinJvm :desktopApp:compileKotlinJvm` → **BUILD SUCCESSFUL**; `./gradlew :homebase-common:jvmTest --tests "id.homebase.core.logging.*"` → **BUILD SUCCESSFUL**. Then manually run the Desktop app (`./gradlew desktopApp:run`), toggle error collection off in Help, force a handled exception, and confirm via the endpoint/Sentry dashboard (or a local capture) that **nothing** is sent; toggle on and confirm one record arrives with no PII.

### Step 8 — Final guardrail diff
Confirm you touched only in-scope files and no seam/other-platform file changed.

Verify: `git diff --stat -- homebase-common/src/androidMain homebase-common/src/nativeMain homebase-common/src/wasmJsMain` → **no output**; `git diff -- homebase-common/.../logging/CrashReporter.kt homebase-common/.../logging/ErrorCollectionHandler.kt` → **no output**.

## Test plan

**New test:** `homebase-common/src/jvmTest/kotlin/id/homebase/core/logging/DesktopCrashReporterTest.kt`.
- Cases as enumerated in Step 6; the **named regression** is "case 1: `setErrorCollectionEnabled(false)` suppresses `crashlyticsRecordException` sends" — directly covering the finding that the JVM `setErrorCollectionEnabled` was a no-op and could not gate anything.
- Plus a redaction assertion (case 3) so a future change that starts leaking `throwable.message` or a token into the payload fails the build.
- Model after: `homebase-common/src/jvmTest/kotlin/id/homebase/core/logging/LoggerConfigTest.kt` (JVM-only, `@AfterTest` cleanup of shared singleton state, single-JVM contamination caveat).
- Uses a **fake** `DesktopCrashTransport` (interface substitution), not a mock. `MockEngine`/`ktor-client-mock` only if you choose to test the real ktor impl end-to-end (optional; the interface fake is sufficient and preferred).
- Verify command: `./gradlew :homebase-common:jvmTest --tests "id.homebase.core.logging.*"`.

## Done criteria
- [ ] Operator confirmed backend choice (A or B) and supplied required config references (recorded in the PR/commit message — values referenced, never pasted).
- [ ] `CrashReporter.jvm.kt` actuals forward to the chosen backend and early-return when `desktopErrorCollectionEnabled == false`.
- [ ] `ErrorCollectionHandler.jvm.kt` `setErrorCollectionEnabled` flips the gate the actuals read (no longer a no-op).
- [ ] `Main.kt` applies `setErrorCollectionEnabled(userPreferences.errorCollectionEnabled)` at startup after `UserPreferences` is resolved.
- [ ] `DesktopCrashReporterTest` exists with the four cases; the gate-suppression case and the redaction case both assert.
- [ ] `./gradlew :homebase-common:compileKotlinJvm` → BUILD SUCCESSFUL.
- [ ] `./gradlew :desktopApp:compileKotlinJvm` → BUILD SUCCESSFUL.
- [ ] `./gradlew :homebase-common:jvmTest --tests "id.homebase.core.logging.*"` → BUILD SUCCESSFUL.
- [ ] `git diff` shows no change to common-seam files or to android/native/wasmJs actuals.
- [ ] No secret value appears in any tracked source file (DSN/token referenced by config key only).
- [ ] This plan's row is updated in `plans/README.md` (create the file with a header row if it does not yet exist).

## STOP conditions
- **STOP at Step 2** until the operator explicitly chooses Option A or B and (for B) names the ingest URL + auth scheme + payload shape, or (for A) provisions a DSN. Do not invent an endpoint or DSN. This is the plan's hard gate.
- STOP if the Drift-check diff (header) prints any line — re-read the cited files and reconcile before coding.
- STOP if implementing the chosen backend would require editing `CrashReporter.kt`/`ErrorCollectionHandler.kt`/`CrashlyticsLogWriter`/`LoggerConfig.kt` (the common seam) — that means the approach is wrong; the JVM `actual` must satisfy the existing signatures.
- STOP if you cannot redact the payload to a guaranteed-no-PII shape (no message verbatim that may carry user text, no tokens, no account/device identifiers) — shipping crash reports with PII is worse than the status quo.
- STOP if you find yourself adding a `try { } catch (_: Exception) { }` purely to make a send failure disappear (CLAUDE.md symptom-patch rule) — a failed send should be logged, not silently swallowed.

## Maintenance notes
- A reviewer should scrutinize: (1) the gate is checked **inside** both actuals (not only at the call site), so a future caller that bypasses `CrashlyticsLogWriter` still respects the toggle; (2) the install id is freshly generated and persisted, NOT derived from any account/device identifier; (3) the redactor actually strips the planted token in the test; (4) the fatal path stays fire-and-forget and never blocks the AWT EDT or the crash sequence (`MainThreadWatchdog` is watching the EDT — a blocking flush here would itself be logged as a stall).
- The fatal best-effort send shares iOS's documented race (`CrashReporter.native.kt:24-34`): a hard crash may terminate before the POST completes. That is acceptable; the breadcrumb/non-fatal path covers handled exceptions, and the on-disk `homebase.log` remains the source of truth for fatals.
- Deferred follow-ups: a small bounded queue + retry-on-next-launch for sends that fail offline (Option B); symbolication/source-map upload if Option B ever wants readable JVM stacks server-side; consider a one-time consent prompt on first Desktop launch if legal wants explicit opt-in rather than the current default-on `errorCollectionEnabled`.
- If `errorCollectionEnabled`'s default ever changes from `true` (UserPreferences.kt:64), update `desktopErrorCollectionEnabled`'s initializer in `DesktopCrashTransport.kt` to match, or pre-fatal breadcrumbs will use a stale default until Step 5's startup apply runs.
