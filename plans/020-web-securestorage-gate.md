# Plan 020: Gate the Web SecureStorage plaintext-localStorage gap before Web ships

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md (create that file if it does not exist — see Maintenance notes).
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-api/src/wasmJsMain/kotlin/id/homebase/api/storage/SecureStorage.web.kt homebase-api/src/commonMain/kotlin/id/homebase/api/storage/SecureStorage.kt`. On mismatch with the Current state excerpts, STOP.
> NOTE on the drift base: at planning time commit `45e2832e` **is HEAD** (the merge of PR #728), so `45e2832e..HEAD` is an empty range and the diff prints nothing — that is the expected "no drift" result while HEAD has not moved. If HEAD has since advanced, the command shows the real diff for these two files; if either file changed in a way that conflicts with the Current state excerpts below, STOP.

## Status
Priority P3; Effort M; Risk MED; Depends on: none; Category security; Planned at: commit 45e2832e, 2026-06-14.

## Why this matters
`SecureStorage` is the single sink for the app's most sensitive material: the YouAuth client-auth token, the request-encryption shared secret, and the SQLCipher/database encryption key. The `expect` declaration's KDoc promises "platform-native secure storage mechanisms," and Android (Android KeyStore + AES-GCM), iOS (Keychain), and Desktop (PKCS12 KeyStore + AES-GCM) all honour that. The wasmJs (Web) actual does not: it writes every value verbatim into browser `localStorage`. `localStorage` is plaintext, same-origin-readable by any injected/third-party script (one XSS = full token + DB-key exfiltration), and survives across sessions on shared machines. Browsers expose **no** true secure-storage primitive, so this is not a "find the right API" gap — it is a place where the abstraction silently lies about its guarantee. Web is currently partial / non-shipping, which makes now the cheap moment to install an honest guard: refuse to persist credential-class keys on Web (keep them in-memory for the session, force re-auth next session) and document the Web threat model in the KDoc so no future contributor ships Web believing tokens are protected. The heavier WebCrypto-encryption path is recorded as a deferred follow-up (it does not stop XSS-time theft, so it is not worth blocking on).

## Current state

### 1. The `expect` + KDoc that makes the promise — `homebase-api/src/commonMain/kotlin/id/homebase/api/storage/SecureStorage.kt`
The KDoc lists Android/iOS/Desktop and silently omits Web (lines 5-12):
```kotlin
5  /**
6   * Secure key-value storage using platform-native secure storage mechanisms.
7   *
8   * Platform implementations:
9   * - Android: Android KeyStore with AES-GCM encryption
10  * - iOS: Keychain Services
11  * - Desktop: Java KeyStore with AES-GCM encryption
12  */
13  expect object SecureStorage {
14      fun put(key: String, value: String)
...
26      fun get(key: String): String?
32      fun remove(key: String)
39      fun contains(key: String): Boolean
42      fun clear()
43  }
```

### 2. The offending Web actual — `homebase-api/src/wasmJsMain/kotlin/id/homebase/api/storage/SecureStorage.web.kt`
Plaintext delegation to `localStorage`, no encryption, no key classification (whole file, 26 lines):
```kotlin
1  package id.homebase.api.storage
2
3  import kotlinx.browser.localStorage
4
5  actual object SecureStorage {
6
7      actual fun put(key: String, value: String) {
8          localStorage.setItem(key, value)        // <-- plaintext credential storage
9      }
10
11     actual fun get(key: String): String? {
12         return localStorage.getItem(key)
13     }
14
15     actual fun remove(key: String) {
16         localStorage.removeItem(key)
17     }
18
19     actual fun contains(key: String): Boolean {
20         return localStorage.getItem(key) != null
21     }
22
23     actual fun clear() {
24         localStorage.clear()
25     }
26 }
```

### 3. The credential-class keys this protects (the concrete list the gate must refuse)
- `homebase-api/src/commonMain/kotlin/id/homebase/api/youauth/YouAuthStorageKeys.kt`
  - line 9 `const val CLIENT_AUTH_TOKEN = "youauth_client_auth_token"` — auth token
  - line 12 `const val SHARED_SECRET = "youauth_shared_secret"` — request-encryption secret
  - line 6 `const val IDENTITY = "youauth_identity"` — auth-state (domain name; not a secret, but presence of all three = "logged in")
  - line 15 `const val USERNAME = "youauth_username"` — NOT routed through SecureStorage (uses `SharedPreferences`, see UsernameStorage.kt); ignore.
- `homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/DatabaseKeyManager.kt`
  - line 9 `private const val KEY_DB_ENCRYPTION = "odin_db_encryption_key"` — the SQLCipher/DB key (highest sensitivity). Used at lines 12/17/24.
- Call sites that read/write these via `SecureStorage`: `CredentialStorage.kt` (lines 16-59), `YouAuthFlowManager.kt` (lines 154-155), `DatabaseKeyManager.kt` (lines 12-24).

### 4. The sensitive raw key strings, gathered for the gate
The three secret-bearing raw keys are: `"youauth_client_auth_token"`, `"youauth_shared_secret"`, `"odin_db_encryption_key"`. Treat `"youauth_identity"` as sensitive too (it is half the login state and there is no reason to persist it when the secrets won't persist). These are the **literal** strings `localStorage` would see, because callers pass the constants' values.

### 5. Logging convention to match (Kermit `Logger`, used throughout `homebase-api/commonMain`)
Exemplar — `homebase-api/src/commonMain/kotlin/id/homebase/api/youauth/YouAuthFlowManager.kt`:
```kotlin
4   import co.touchlab.kermit.Logger
122 Logger.e(tag = TAG) { "Missing query params in callback URL" }
```
`TAG` is a `private const val` per file. Use `Logger.w` for the gate (it's a deliberate refusal, not a crash). Note: `kotlinx.browser.localStorage` is wasmJs-only; `Logger` is multiplatform and available in `wasmJsMain`.

### 6. wasmJs test infrastructure (a real browser, so `localStorage` exists in tests)
`homebase-api/build.gradle.kts`:
- lines 49-63: `wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }` — wasmJs tests run in headless Chrome, so `kotlinx.browser.localStorage` is a real Storage object at test time.
- lines 80-82: the `wasmJsTest` source set already exists and has resources wired.
- lines 159-180: a hand-maintained `wasmJsDbBackedTestClasses` exclusion list skips DB-backed common tests on wasm (SQLDelight has no sql.js test driver). A pure-`localStorage` test does NOT touch SQLDelight, so it will run normally and must NOT be added to that list.
- Existing wasmJs test tree: `homebase-api/src/wasmJsTest/kotlin/id/homebase/api/{video,sync/database}` — you will add a new `storage/` package alongside.

Convention exemplar for a `kotlin.test` test: `homebase-api/src/commonTest/kotlin/id/homebase/api/crypto/AesGcmTest.kt` (uses `import kotlin.test.Test` / `assertEquals` / `assertNull` / `assertTrue`).

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (see header note) | `git diff --stat 45e2832e..HEAD -- homebase-api/src/wasmJsMain/kotlin/id/homebase/api/storage/SecureStorage.web.kt homebase-api/src/commonMain/kotlin/id/homebase/api/storage/SecureStorage.kt` | Empty output (HEAD unmoved), or a diff that still matches the Current state excerpts |
| Compile Web (PRIMARY gate) | `./gradlew :homebase-api:compileKotlinWasmJs --no-configuration-cache` | `BUILD SUCCESSFUL` |
| Compile Desktop (KDoc edit is in commonMain — verify common still builds) | `./gradlew :homebase-api:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Run the new wasmJs test (browser harness) | `./gradlew :homebase-api:wasmJsTest --no-configuration-cache --no-parallel` | `BUILD SUCCESSFUL`; the new `WebSecureStorageGateTest` passes |
| Confirm no plaintext-secret regression site reappears | `git grep -n "localStorage.setItem" -- homebase-api/src/wasmJsMain` | Only the gated `put` in SecureStorage.web.kt |

## Scope
**In scope (only these files):**
- `homebase-api/src/wasmJsMain/kotlin/id/homebase/api/storage/SecureStorage.web.kt` — add the sensitivity gate + in-memory session store; do NOT persist sensitive keys.
- `homebase-api/src/commonMain/kotlin/id/homebase/api/storage/SecureStorage.kt` — extend the KDoc with the explicit Web threat-model paragraph (Web is currently omitted from the secure list).
- `homebase-api/src/wasmJsTest/kotlin/id/homebase/api/storage/WebSecureStorageGateTest.kt` — NEW test (Step 3).

**Out of scope (do NOT touch — one-line why each):**
- `homebase-api/src/androidMain/.../SecureStorage.android.kt` — already encrypts via Android KeyStore; correct.
- `homebase-api/src/nativeMain/.../SecureStorage.native.kt` — already uses iOS Keychain; correct.
- `homebase-api/src/jvmMain/.../SecureStorage.jvm.kt` — already uses PKCS12 + AES-GCM; correct.
- `YouAuthStorageKeys.kt`, `CredentialStorage.kt`, `YouAuthFlowManager.kt`, `DatabaseKeyManager.kt` — callers are platform-agnostic; the gate lives in the Web actual so callers stay unchanged. Changing them would leak Web concerns into common code.
- `homebase-api/build.gradle.kts` `wasmJsDbBackedTestClasses` list (lines 159-174) — the new test does not use SQLDelight; do NOT add it there.

## Steps

1. **Add the sensitivity gate + in-memory session store to the Web actual.**
   Edit `homebase-api/src/wasmJsMain/kotlin/id/homebase/api/storage/SecureStorage.web.kt`. Replace the whole `actual object` body so that:
   - A `private val sensitiveKeys: Set<String>` holds the four raw key strings: `"youauth_client_auth_token"`, `"youauth_shared_secret"`, `"odin_db_encryption_key"`, `"youauth_identity"`.
     - Reference them by literal here (the Web actual is in `homebase-api` and CAN'T import `DatabaseKeyManager`'s `private` constant; `YouAuthStorageKeys` is `public` but importing it from a low-level storage file is acceptable — EITHER literal set OR import is fine, but a literal set keeps the gate self-contained and adds a `// keep in sync with` comment pointing at the two constant sites). Prefer the literal set with the sync comment.
   - A `private val memory = mutableMapOf<String, String>()` is the session-only store for sensitive keys (cleared on page reload — exactly the "force re-auth per session" behaviour we want).
   - `put(key, value)`: if `key in sensitiveKeys` → store in `memory`, do NOT call `localStorage.setItem`, and `Logger.w(tag = TAG) { "Refusing to persist sensitive key '$key' on Web (no browser secure store); keeping in-memory for this session only." }`. Otherwise `localStorage.setItem(key, value)` as before.
     - STOP-WORTHY DETAIL: do NOT log the `value`. Log only the key name.
   - `get(key)`: if `key in sensitiveKeys` → return `memory[key]`. Otherwise `localStorage.getItem(key)`.
   - `remove(key)`: `memory.remove(key)` AND `localStorage.removeItem(key)` (harmless for non-sensitive keys; defensive in case a sensitive key was persisted by an older build).
   - `contains(key)`: if `key in sensitiveKeys` → `memory.containsKey(key)`. Otherwise `localStorage.getItem(key) != null`.
   - `clear()`: `memory.clear()` then `localStorage.clear()`.
   - Add `import co.touchlab.kermit.Logger` and `private const val TAG = "SecureStorage"`.
   Verify: `./gradlew :homebase-api:compileKotlinWasmJs --no-configuration-cache` -> `BUILD SUCCESSFUL`.

2. **Document the Web threat model in the expect KDoc.**
   Edit `homebase-api/src/commonMain/kotlin/id/homebase/api/storage/SecureStorage.kt`. In the KDoc block (lines 5-12) add a Web line to the platform list and a short threat-model paragraph, e.g.:
   ```
   * - Web (wasmJs): NO browser secure-storage primitive exists. Non-sensitive keys go to
   *   localStorage (plaintext, same-origin readable). Credential-class keys (auth token,
   *   shared secret, DB encryption key, identity) are NEVER persisted — they are held
   *   in-memory for the session only, so a Web session does not survive a reload and the
   *   user must re-authenticate. See SecureStorage.web.kt. localStorage remains readable by
   *   any same-origin script (XSS), which is why we do not persist secrets there even encrypted.
   ```
   Keep it factual; do not claim Web is "secure." This is the only edit in commonMain.
   Verify: `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL` (a KDoc-only edit must not break the common compile).

3. **Add the regression test.** Create `homebase-api/src/wasmJsTest/kotlin/id/homebase/api/storage/WebSecureStorageGateTest.kt` (model after `commonTest/.../crypto/AesGcmTest.kt` for `kotlin.test` imports). It MUST:
   - `import kotlinx.browser.localStorage`, `import id.homebase.api.storage.SecureStorage`, `kotlin.test.*`.
   - `@BeforeTest` clear both `SecureStorage.clear()` and any leftover localStorage state.
   - **Case A (the core regression):** for each sensitive raw key, `SecureStorage.put(key, "secret-value")`, then assert `localStorage.getItem(key) == null` (the secret was NOT written to localStorage) AND `SecureStorage.get(key) == "secret-value"` (it is retrievable in-session) AND `SecureStorage.contains(key)` is `true`.
   - **Case B (non-sensitive still persists):** `SecureStorage.put("some_pref", "ok")`; assert `localStorage.getItem("some_pref") == "ok"`.
   - **Case C (remove + clear):** after putting a sensitive and a non-sensitive key, `SecureStorage.remove(sensitiveKey)` → `get` returns null; `SecureStorage.clear()` → both `memory` and localStorage empty (`localStorage.getItem("some_pref") == null`).
   - Use the literal raw key strings (`"youauth_shared_secret"`, etc.) so the test pins the exact gate behaviour and fails loudly if someone narrows the set.
   - Do NOT add this class to `wasmJsDbBackedTestClasses` (it never touches SQLDelight).
   Verify: `./gradlew :homebase-api:wasmJsTest --no-configuration-cache --no-parallel` -> `BUILD SUCCESSFUL` and the test passes.
   - If the wasmJs browser test harness is unavailable in your environment (no Chrome / Karma cannot start), STOP and report; record the manual verification fallback below instead of fabricating a green run (see Test plan → manual fallback).

4. **Confirm no other plaintext-secret sink exists on Web and the build is whole.**
   Verify: `git grep -n "localStorage.setItem" -- homebase-api/src/wasmJsMain` -> the ONLY hit is the gated `put` in SecureStorage.web.kt.
   Verify: `./gradlew :homebase-api:compileKotlinWasmJs --no-configuration-cache` -> `BUILD SUCCESSFUL`.

## Test plan
- **New test:** `homebase-api/src/wasmJsTest/kotlin/id/homebase/api/storage/WebSecureStorageGateTest.kt` (cases A/B/C above). Case A is the regression that locks the fix: it fails on today's code (today `localStorage.getItem("youauth_shared_secret")` returns the secret) and passes after Step 1.
- **Model after:** `homebase-api/src/commonTest/kotlin/id/homebase/api/crypto/AesGcmTest.kt` for `kotlin.test` style; this is a wasmJs-only test because it asserts against the real `localStorage`.
- **Verify command:** `./gradlew :homebase-api:wasmJsTest --no-configuration-cache --no-parallel`.
- **Manual fallback (only if the browser harness cannot run here):** document in your completion report that the test file is in place and compiles via `./gradlew :homebase-api:compileTestKotlinWasmJs --no-configuration-cache` (compile-only gate), and that a maintainer with a Chrome-capable CI must run `wasmJsTest` to get the green. Do NOT delete the test or mark the plan done on a compile-only pass without saying so explicitly.

## Done criteria
- [ ] `SecureStorage.web.kt`: `put`/`get`/`contains` route the four sensitive raw keys to an in-memory map and never call `localStorage.setItem` for them; non-sensitive keys still use `localStorage`. A `Logger.w` fires on a refused persist, logging the key name only (never the value).
- [ ] `SecureStorage.kt` KDoc documents the Web row + threat model (Web no longer silently omitted).
- [ ] New `WebSecureStorageGateTest` exists with cases A/B/C; case A asserts no sensitive value reaches `localStorage`.
- [ ] `./gradlew :homebase-api:compileKotlinWasmJs --no-configuration-cache` → BUILD SUCCESSFUL.
- [ ] `./gradlew :homebase-api:compileKotlinJvm` → BUILD SUCCESSFUL.
- [ ] `./gradlew :homebase-api:wasmJsTest --no-configuration-cache --no-parallel` → BUILD SUCCESSFUL (or, if harness unavailable here, compileTestKotlinWasmJs passes and the limitation is reported).
- [ ] `git grep -n "localStorage.setItem" -- homebase-api/src/wasmJsMain` → only the gated call.
- [ ] No file outside the In-scope list is modified (`git diff --name-only` shows only the three files).
- [ ] plans/README.md row updated (create file if absent).

## STOP conditions (specific to this plan)
- Drift: if HEAD has advanced and either `SecureStorage.web.kt` or `SecureStorage.kt` no longer matches the Current state excerpts (e.g. someone already added a gate, or migrated Web off `localStorage`), STOP and report what changed — the fix may be partially or fully present.
- If you find a caller that depends on Web auth surviving a reload (i.e. Web product UX genuinely requires session persistence), STOP and report: approach (a) deliberately drops persistence, and the heavier WebCrypto path (approach b) would be needed instead — that is a scope change, not a silent substitution.
- If the wasmJs browser test harness cannot start (no Chrome/Karma), STOP at Step 3, keep the test file, fall back to the compile-only gate, and say so. Do NOT add the new test to `wasmJsDbBackedTestClasses` to "skip it green."
- If you find yourself tempted to silence the `Logger.w` or to persist secrets "just for dev convenience," STOP — that re-opens the exact gap this plan closes (CLAUDE.md: do not patch around a symptom).

## Maintenance notes
- **What a reviewer should scrutinize:** (1) the `sensitiveKeys` set is the load-bearing list — confirm it covers every secret-class key. At plan time those are `youauth_client_auth_token`, `youauth_shared_secret`, `odin_db_encryption_key`, and `youauth_identity`. If a new secret key is added to `YouAuthStorageKeys.kt` or `DatabaseKeyManager.kt`, it MUST be added here too; the literal set carries a "keep in sync with" comment for exactly this reason. (2) Confirm the `Logger.w` never interpolates the value. (3) Confirm `remove`/`clear` purge both stores.
- **Deferred follow-up (approach b, heavier):** AES-GCM-encrypt values with a non-extractable WebCrypto `CryptoKey` stored in IndexedDB before writing to `localStorage`. This raises the bar against *offline disk* inspection but does NOT defend against XSS (a same-origin script can call the same WebCrypto key to decrypt), so it is not a substitute for not-persisting secrets; it would only matter if Web product UX requires cross-session login. Track it as its own plan when Web moves toward shipping.
- **Why approach (a) over (b) now:** Web is partial/non-shipping; (a) is smaller, honest about the XSS reality, and costs only "re-auth per session" on a platform with no production users yet. Revisit when Web ships.
- **Plans index:** plans/README.md does not exist at plan time. If it is still absent when you finish, create it with a one-line table header and this plan's row (num, title, status, category); otherwise append the row.
