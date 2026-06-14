# Plan 022: Add a characterization test pinning CredentialsManager's flow-emission contract

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git -C /Users/biswa/Documents/GitHub/chat-kmp diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/CredentialsManager.kt homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/ApiCredentials.kt homebase-api/src/commonMain/kotlin/id/homebase/api/common/OdinId.kt homebase-api/build.gradle.kts`. On mismatch with the Current state excerpts below, STOP.

## Status
Priority P2; Effort S; Risk LOW; Depends on: none; Category tests; Planned at: commit 45e2832e, 2026-06-14.

Drift check at planning time: codeMatchedFinding = TRUE. HEAD == 45e2832e at planning time. All cited line numbers verified by direct read. No drift.

## Why this matters
`CredentialsManager` is the app-wide authentication gate: its `credentialsFlow` (a `StateFlow<ApiCredentials?>`) is what UI/navigation observes to decide whether the user is signed in and into which domain. Its mutating methods have **subtle, asymmetric** flow-emission behaviour: some methods update the flow, some deliberately do not, and the two `setActiveCredentials` overloads behave differently. These asymmetries are an observable contract, but **no test exists** that pins them — a refactor could silently make `storeCredentials` start (or `setActiveCredentials` stop) emitting, flipping the whole app's auth state, and nothing would catch it. This plan adds a pure in-memory **characterization test** that locks the CURRENT behaviour exactly as written. It is intentionally NOT a fix: do not "correct" any asymmetry; if a future change deliberately changes behaviour, the engineer must consciously update this test, which is the whole point.

## Current state

### File under test (DO NOT MODIFY)
`homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/CredentialsManager.kt` — the credential store. Verified excerpts (real line numbers):

```kotlin
11  class CredentialsManager {
12      private val mutex = Mutex()
13      private val storedCredentials = mutableMapOf<String, ApiCredentials>()
14
15      private var activeCredentials: ApiCredentials? = null
16
17      private val _credentialsFlow = MutableStateFlow<ApiCredentials?>(null)
18      val credentialsFlow: StateFlow<ApiCredentials?> = _credentialsFlow.asStateFlow()
...
28      suspend fun storeCredentials(credentials: ApiCredentials) = mutex.withLock {
29          storedCredentials[credentials.domain.domainName] = credentials   // <-- NO flow update
30      }
31
32      suspend fun removeCredentials(domain: OdinId) = mutex.withLock {
33          if (activeCredentials?.domain == domain) {
34              activeCredentials = null
35          }
36          storedCredentials.remove(domain.domainName)                       // <-- NO flow update
37      }
38
39      suspend fun removeAllCredentials() = mutex.withLock {
40          _credentialsFlow.update { null }                                  // <-- emits null
41          activeCredentials = null
42          storedCredentials.clear()
43      }
...
49      suspend fun setActiveCredentials(credentials: ApiCredentials) = mutex.withLock {
50          storedCredentials[credentials.domain.domainName] = credentials
51          activeCredentials = credentials
52          _credentialsFlow.update { credentials }                          // <-- emits creds
53      }
54
55      suspend fun setActiveCredentials(domain: String) = mutex.withLock {
56          activeCredentials = storedCredentials[domain]                     // <-- NO flow update
57              ?: throw IllegalArgumentException("No credentials found for domain: $domain")
58      }
59
60      suspend fun removeActiveCredentials() = mutex.withLock {
61          activeCredentials = null
62          _credentialsFlow.update { null }                                  // <-- emits null
63      }
64
65      suspend fun requireActiveCredentials(): ApiCredentials = mutex.withLock {
66          activeCredentials
67              ?: throw IllegalStateException("No active credentials set")
68      }
69
70      suspend fun requireActiveDomain(): OdinId = mutex.withLock {
71          activeCredentials?.domain
72              ?: throw IllegalStateException("No active credentials set")
73      }
74  }
```

Other read-accessors (verified): `hasActiveCredentials()` (20-22), `getActiveDomain()` (24-26), `getActiveCredentials()` (45-47) — all return-only, no flow update, no throw.

**The behavioural contract this test must pin (CURRENT behaviour, characterization):**

| Method | Flow emission | Throws |
|---|---|---|
| initial state | `credentialsFlow.value == null` | — |
| `storeCredentials(c)` | flow UNCHANGED (still whatever it was) | no |
| `setActiveCredentials(c: ApiCredentials)` | flow becomes `c` | no |
| `setActiveCredentials(domain: String)` known | flow UNCHANGED | no |
| `setActiveCredentials(domain: String)` unknown | flow UNCHANGED | `IllegalArgumentException` |
| `removeCredentials(domain)` | flow UNCHANGED (even when it was the active one) | no |
| `removeActiveCredentials()` | flow becomes `null` | no |
| `removeAllCredentials()` | flow becomes `null` | no |
| `requireActiveCredentials()` when none active | — | `IllegalStateException` |
| `requireActiveDomain()` when none active | — | `IllegalStateException` |

Note the two genuinely surprising rows to assert explicitly: `removeCredentials` of the **currently active** domain clears `activeCredentials` internally **but leaves `credentialsFlow.value` stale (non-null)** — observers do NOT see it go away. And `setActiveCredentials(domain: String)` (the string overload) sets the active creds but does NOT emit — so `getActiveCredentials()` and `credentialsFlow.value` can disagree.

### Construction helpers (read-only context for building test fixtures)
`homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/ApiCredentials.kt` — `data class` with a **private** constructor; build via `ApiCredentials.create(domain, clientAccessToken, sharedSecret)`. `init` requires `clientAccessToken.isNotBlank()` and `sharedSecret.unsafeBytes.isNotEmpty()` — so pass a non-blank token and a non-empty byte array.

`homebase-api/src/commonMain/kotlin/id/homebase/api/common/OdinId.kt` — has a public `constructor(identifier: String)` (line 83) that validates/normalizes the string as an RFC domain via `AsciiDomainName`. Use a real domain like `"alice.example.com"`. `OdinId.equals` compares by hash of the domain name (line 87-90), so two `OdinId("alice.example.com")` are equal — `removeCredentials` matching works.

`homebase-api/src/commonMain/kotlin/id/homebase/api/common/SecureByteArray.kt` — `class SecureByteArray(private val bytes: ByteArray)`. Construct with `SecureByteArray(byteArrayOf(1, 2, 3))`.

### Test wiring (verified)
`homebase-api/build.gradle.kts` line 226-231 — `commonTest.dependencies` already include `libs.kotlin.test` and `libs.kotlinx.coroutines.test`. So `kotlin.test.*` and `kotlinx.coroutines.test.runTest` are available in `commonTest`. **Turbine is NOT a dependency** (absent from `gradle/libs.versions.toml`) — do NOT use it. The flow is a `StateFlow`, so read its current value directly with `.value`; no collector is needed.

### Convention + exemplar to match
Tests use FAKES not mocks, and `runTest` for suspend code. Model the new test after
`homebase-api/src/commonTest/kotlin/id/homebase/api/video/VideoCompressionServiceSerializationTest.kt`
(class layout, `kotlinx.coroutines.test.runTest`, `kotlin.test.Test`/`assertEquals`). For
exception assertions use `kotlin.test.assertFailsWith`. This new test needs **no fakes at all** —
`CredentialsManager` is pure in-memory.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check | `git -C /Users/biswa/Documents/GitHub/chat-kmp diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/CredentialsManager.kt homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/ApiCredentials.kt homebase-api/src/commonMain/kotlin/id/homebase/api/common/OdinId.kt homebase-api/build.gradle.kts` | empty output (no changes since plan) |
| Compile the new test | `./gradlew :homebase-api:compileTestKotlinJvm` | `BUILD SUCCESSFUL` |
| Run just this test class | `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.client.auth.CredentialsManagerTest"` | `BUILD SUCCESSFUL`, all cases pass |
| Run full module JVM suite (final gate) | `./gradlew :homebase-api:jvmTest` | `BUILD SUCCESSFUL` |

Run all gradle commands from the repo root `/Users/biswa/Documents/GitHub/chat-kmp`.

## Scope
**In scope (create only):**
- `homebase-api/src/commonTest/kotlin/id/homebase/api/client/auth/CredentialsManagerTest.kt` — the new characterization test.

**Out of scope (DO NOT TOUCH):**
- `homebase-api/.../client/auth/CredentialsManager.kt` — this is a characterization test; the asymmetries are pinned, not fixed. No source change.
- `homebase-api/.../client/auth/ApiCredentials.kt`, `.../common/OdinId.kt`, `.../common/SecureByteArray.kt` — read-only fixtures; don't add test-only constructors.
- `homebase-api/build.gradle.kts` — test deps already present; do NOT add Turbine or any dependency.
- `gradle/libs.versions.toml` — no new versions.

## Steps

1. **Drift check.** Run the Drift-check command from the table.
   Verify: command output is empty → no drift; if non-empty, STOP (see STOP conditions).

2. **Re-read the source before writing** (mandatory for a characterization test): open
   `homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/CredentialsManager.kt` and confirm each row of the contract table above still matches the real code (especially lines 28-30, 36, 52, 55-58, 60-63). If any line disagrees with the table, STOP and report which row drifted — do not invent behaviour.
   Verify: every contract-table row matches the current method body → proceed.

3. **Create the test file** at
   `homebase-api/src/commonTest/kotlin/id/homebase/api/client/auth/CredentialsManagerTest.kt`
   with the content below. It uses only `kotlin.test` + `kotlinx.coroutines.test.runTest`, no fakes, no Turbine. Read `credentialsFlow.value` directly.

   ```kotlin
   package id.homebase.api.client.auth

   import id.homebase.api.common.OdinId
   import id.homebase.api.common.SecureByteArray
   import kotlinx.coroutines.test.runTest
   import kotlin.test.Test
   import kotlin.test.assertEquals
   import kotlin.test.assertFailsWith
   import kotlin.test.assertNull
   import kotlin.test.assertSame

   /**
    * Characterization test for [CredentialsManager] — the app-wide auth gate.
    *
    * `credentialsFlow` is the StateFlow that UI/navigation observe to decide whether the
    * user is signed in. Its mutators have *asymmetric* emission behaviour: some update the
    * flow, some deliberately do not, and the two `setActiveCredentials` overloads differ.
    * This test PINS the CURRENT behaviour exactly. It is NOT a bug fix: if you intend to
    * change an asymmetry, you must consciously update the assertions here — that friction
    * is the point. See the contract table in plans/022-credentialsmanager-test.md.
    *
    * Pure in-memory; no fakes. Runs on JVM CI (and every other target).
    */
   class CredentialsManagerTest {

       private fun creds(domain: String): ApiCredentials =
           ApiCredentials.create(
               domain = OdinId(domain),
               clientAccessToken = "token-$domain",
               sharedSecret = SecureByteArray(byteArrayOf(1, 2, 3)),
           )

       private val alice = creds("alice.example.com")
       private val bob = creds("bob.example.com")

       @Test
       fun flowStartsNull() = runTest {
           val mgr = CredentialsManager()
           assertNull(mgr.credentialsFlow.value)
       }

       @Test
       fun storeCredentialsDoesNotEmitOnFlow() = runTest {
           val mgr = CredentialsManager()
           mgr.storeCredentials(alice)
           // store only writes the map; the flow gate stays untouched.
           assertNull(mgr.credentialsFlow.value)
           // ...but it IS retrievable via the string overload now (no throw):
           mgr.setActiveCredentials("alice.example.com")
           assertSame(alice, mgr.getActiveCredentials())
       }

       @Test
       fun setActiveCredentialsEmitsOnFlow() = runTest {
           val mgr = CredentialsManager()
           mgr.setActiveCredentials(alice)
           assertSame(alice, mgr.credentialsFlow.value)
           assertSame(alice, mgr.getActiveCredentials())

           mgr.setActiveCredentials(bob)
           assertSame(bob, mgr.credentialsFlow.value)
       }

       @Test
       fun setActiveByDomainStringDoesNotEmitOnFlow() = runTest {
           val mgr = CredentialsManager()
           mgr.storeCredentials(alice)
           mgr.setActiveCredentials("alice.example.com")
           // active creds set, but the flow overload deliberately does NOT emit:
           assertSame(alice, mgr.getActiveCredentials())
           assertNull(mgr.credentialsFlow.value)
       }

       @Test
       fun setActiveByUnknownDomainThrowsIllegalArgument() = runTest {
           val mgr = CredentialsManager()
           assertFailsWith<IllegalArgumentException> {
               mgr.setActiveCredentials("nobody.example.com")
           }
       }

       @Test
       fun removeCredentialsOfActiveLeavesFlowStale() = runTest {
           val mgr = CredentialsManager()
           mgr.setActiveCredentials(alice)          // flow == alice
           mgr.removeCredentials(OdinId("alice.example.com"))
           // internal active cleared, getter sees it gone...
           assertNull(mgr.getActiveCredentials())
           // ...but the flow is NOT updated by removeCredentials — observers still see alice.
           assertSame(alice, mgr.credentialsFlow.value)
       }

       @Test
       fun removeActiveCredentialsEmitsNull() = runTest {
           val mgr = CredentialsManager()
           mgr.setActiveCredentials(alice)
           mgr.removeActiveCredentials()
           assertNull(mgr.credentialsFlow.value)
           assertNull(mgr.getActiveCredentials())
       }

       @Test
       fun removeAllCredentialsEmitsNull() = runTest {
           val mgr = CredentialsManager()
           mgr.setActiveCredentials(alice)
           mgr.storeCredentials(bob)
           mgr.removeAllCredentials()
           assertNull(mgr.credentialsFlow.value)
           assertNull(mgr.getActiveCredentials())
           // the previously-stored bob is also gone from the map:
           assertFailsWith<IllegalArgumentException> {
               mgr.setActiveCredentials("bob.example.com")
           }
       }

       @Test
       fun requireActiveCredentialsThrowsWhenEmpty() = runTest {
           val mgr = CredentialsManager()
           assertFailsWith<IllegalStateException> { mgr.requireActiveCredentials() }
       }

       @Test
       fun requireActiveDomainThrowsWhenEmpty() = runTest {
           val mgr = CredentialsManager()
           assertFailsWith<IllegalStateException> { mgr.requireActiveDomain() }
       }

       @Test
       fun requireAccessorsReturnWhenActive() = runTest {
           val mgr = CredentialsManager()
           mgr.setActiveCredentials(alice)
           assertSame(alice, mgr.requireActiveCredentials())
           assertEquals(OdinId("alice.example.com"), mgr.requireActiveDomain())
       }
   }
   ```

   Verify: file exists at the path above.

4. **Compile the test source.** Run `./gradlew :homebase-api:compileTestKotlinJvm`.
   Verify: `BUILD SUCCESSFUL`. If it fails to compile, fix only the test file (e.g. an import). Do NOT touch source. Common pitfalls: `OdinId(String)` must be a syntactically valid domain (use `*.example.com`); `ApiCredentials.create` token must be non-blank and secret byte array non-empty.

5. **Run just this test class.** Run
   `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.client.auth.CredentialsManagerTest"`.
   Verify: `BUILD SUCCESSFUL`, all 11 test methods pass. If a test FAILS, the source behaviour disagrees with the pinned contract — STOP and report which assertion failed and what the actual value was; do NOT change the source to make it pass, and do NOT loosen an assertion without re-reading the source to confirm the real current behaviour (this is characterization — assert what the code actually does).

6. **Run the full module JVM suite** (regression gate that nothing else broke). Run `./gradlew :homebase-api:jvmTest`.
   Verify: `BUILD SUCCESSFUL`.

7. **Update `plans/README.md`** — mark the row for plan 022 as done (status/checkbox per that file's existing convention). If `plans/README.md` has no row for 022, add one consistent with the surrounding rows. This is the only non-test file you may edit, and only for the status row.
   Verify: `plans/README.md` shows plan 022 as complete.

## Test plan
- **New test file:** `homebase-api/src/commonTest/kotlin/id/homebase/api/client/auth/CredentialsManagerTest.kt`.
- **Cases (11):** `flowStartsNull`, `storeCredentialsDoesNotEmitOnFlow`, `setActiveCredentialsEmitsOnFlow`, `setActiveByDomainStringDoesNotEmitOnFlow`, `setActiveByUnknownDomainThrowsIllegalArgument`, `removeCredentialsOfActiveLeavesFlowStale`, `removeActiveCredentialsEmitsNull`, `removeAllCredentialsEmitsNull`, `requireActiveCredentialsThrowsWhenEmpty`, `requireActiveDomainThrowsWhenEmpty`, `requireAccessorsReturnWhenActive`.
- **The regressions this pins:** (a) `setActiveCredentials(ApiCredentials)` emitting on the flow while `storeCredentials` and the `setActiveCredentials(String)` overload do not; (b) `removeCredentials` of the active domain leaving the flow stale; (c) `removeActiveCredentials`/`removeAllCredentials` emitting null; (d) the two `require*` accessors throwing `IllegalStateException` when empty and the string overload throwing `IllegalArgumentException` on an unknown domain.
- **Model after:** `homebase-api/src/commonTest/kotlin/id/homebase/api/video/VideoCompressionServiceSerializationTest.kt`.
- **Verify command:** `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.client.auth.CredentialsManagerTest"`.

## Done criteria
- [ ] `homebase-api/src/commonTest/kotlin/id/homebase/api/client/auth/CredentialsManagerTest.kt` exists.
- [ ] No file outside that test (and the `plans/README.md` status row) is modified: `git -C /Users/biswa/Documents/GitHub/chat-kmp status --porcelain` shows only the new test file and `plans/` changes.
- [ ] `./gradlew :homebase-api:compileTestKotlinJvm` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.client.auth.CredentialsManagerTest"` → `BUILD SUCCESSFUL`, all 11 cases pass.
- [ ] `./gradlew :homebase-api:jvmTest` → `BUILD SUCCESSFUL`.
- [ ] `plans/README.md` row for 022 marked done.

## STOP conditions
- Drift check (Step 1) is non-empty, OR any contract-table row disagrees with the real code in Step 2 → STOP; report the diff/mismatch. The source may have been refactored; the plan's pinned behaviour must be re-derived from the new code before writing assertions.
- A test FAILS in Step 5 against the source as-is → STOP and report the failing assertion and the actual value. Do NOT edit `CredentialsManager.kt` to make a test pass (out of scope), and do NOT blindly weaken the assertion — first re-read the source to learn the true current behaviour, then assert that.
- You find yourself wanting to add a dependency (Turbine), a constructor, or any production-code change to make the test compile → STOP; the test must work with the existing `commonTest` deps and public API only.

## Maintenance notes
- This is a **characterization** test: it documents what the code does today, not what it should do. The `removeCredentialsOfActiveLeavesFlowStale` and `setActiveByDomainStringDoesNotEmitOnFlow` cases assert arguably-buggy asymmetries on purpose — a reviewer should read them as "this is the contract today," not as endorsement. If a follow-up plan decides the active-domain removal *should* emit null (so observers log the user out), that plan must update these two assertions and reference this plan number in its commit.
- A reviewer should scrutinize: that `assertSame` is used for identity where the test relies on the exact stored instance flowing through (the manager stores the same `ApiCredentials` reference), and `assertEquals` only where value-equality is intended (`OdinId`, which compares by domain hash).
- Deferred follow-up (separate plan, NOT this one): decide whether `removeCredentials`/`setActiveCredentials(String)` *should* emit on the flow to fix the stale-gate asymmetry. That is a behaviour change with auth-state blast radius and needs its own review — this plan only locks the status quo so that change can't happen by accident.
- If this test is later promoted to run on other targets, note it already lives in `commonTest`, so it compiles and runs on JVM/native/wasmJs unchanged (no DB, no platform deps) — no special exclusion needed in the `wasmJsDbBackedTestClasses` list in `build.gradle.kts`.
