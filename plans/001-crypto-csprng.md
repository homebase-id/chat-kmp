# Plan 001: Route all crypto random bytes through a CSPRNG

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt homebase-api/src/commonTest/kotlin/id/homebase/api/crypto/`. If any in-scope file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P1
- Effort: S
- Risk: LOW
- Depends on: none
- Category: security
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
`ByteArrayUtil.getRndByteArray(nCount)` is the single source of random bytes for the app's per-message cryptography, yet it is backed by `kotlin.random.Random.Default`, which is **not** a CSPRNG — it is a seedable, statistically-predictable PRNG (Kotlin's default is an XorWow-family generator, not OS entropy). That one function feeds the AES-GCM 96-bit nonce (`AesGcm.encrypt`), the AES-CBC IV (`CryptoHelper`), the per-message AES content key **and** its IV (`KeyHeader.newRandom16()`), and `getRandomCryptoGuid()`. For AES-GCM in particular, a predictable/repeating nonce under the same key leaks the GHASH authentication subkey and breaks confidentiality and integrity — a real, exploitable weakness, not a theoretical one. The fix is a one-line delegation to a CSPRNG (`dev.whyoleg.cryptography.random.CryptographyRandom`) that is **already a dependency and already used elsewhere in this exact module**, so there is no new dependency, no API surface change, and no call-site change.

## Current state

### File 1 — the only file to modify (logic)
`homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt`

Import (line 3) and the weak generator (lines 261-266):
```kotlin
// line 3
import kotlin.random.Random
```
```kotlin
// lines 261-266
    /**
     * Generates a cryptographically safe array of random bytes
     */
    fun getRndByteArray(nCount: Int): ByteArray {
        return Random.Default.nextBytes(nCount)
    }
```
The KDoc already *claims* "cryptographically safe"; the implementation does not deliver it. `getRandomCryptoGuid()` (lines 224-226) and `isStrongKey()` (lines 231-259) live in the same object — see Maintenance notes for the latter.

### Proof the CSPRNG is already available in this module (the fix exemplar)
`homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/DatabaseKeyManager.kt`
```kotlin
// line 3
import dev.whyoleg.cryptography.random.CryptographyRandom
```
```kotlin
// line 22
        val newKeyBytes = CryptographyRandom.nextBytes(128)
```
`CryptographyRandom` is declared in `commonMain` of the `cryptography-random` artifact, so it resolves on every KMP target (jvm/android/native/wasmJs). No new Gradle dependency is required.

### Call sites that consume getRndByteArray (DO NOT TOUCH — confirmed exact, for context only)
- `homebase-api/src/commonMain/kotlin/id/homebase/api/client/KeyHeader.kt` lines 120-125 — `newRandom16()` builds both `iv = getRndByteArray(16)` and `aesKey = SecureByteArray(getRndByteArray(16))`.
- `homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/AesGcm.kt` line 61 — `val iv = ByteArrayUtil.getRndByteArray(12) // GCM standard nonce size`.
- `homebase-api/src/commonMain/kotlin/id/homebase/api/client/CryptoHelper.kt` line 73 — `val iv = ByteArrayUtil.getRndByteArray(16)`.
- `ByteArrayUtil.getRandomCryptoGuid()` line 224-226 — `Uuid.fromByteArray(getRndByteArray(16))`.

After this fix, every one of these automatically draws from the CSPRNG with **zero** changes at the call site.

### Existing test to model after
`homebase-api/src/commonTest/kotlin/id/homebase/api/crypto/ByteArrayUtilTest.kt` — note lines 173-197 already exercise `getRndByteArray` size + difference and `getRandomCryptoGuid` uniqueness. The new dedicated test file (Step 2) mirrors this style (`kotlin.test`, `@Test`, `assertEquals`/`assertNotEquals`/`assertTrue`, no mocking framework).

### Convention that applies
`homebase-api` is the lowest layer. Tests are plain `kotlin.test` in `commonTest` (FAKES, never Mockito/MockK). New shared tests go in `src/commonTest/kotlin/...`. No Compose/UI conventions apply to this purely cryptographic util.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check before editing | `git diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt homebase-api/src/commonTest/kotlin/id/homebase/api/crypto/` | empty output (no drift) |
| Compile homebase-api on JVM (fast gate) | `./gradlew :homebase-api:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Run homebase-api JVM tests | `./gradlew :homebase-api:jvmTest --rerun-tasks` | `BUILD SUCCESSFUL`, new tests pass |
| Confirm weak generator is gone | `grep -n "Random.Default" homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt` | no output |
| Confirm stray import removed | `grep -n "import kotlin.random.Random" homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt` | no output |
| Confirm in-scope-only changes | `git status --porcelain` | only `ByteArrayUtil.kt`, the new test, `plans/001-crypto-csprng.md`, `plans/README.md` |
| (Optional, macOS host) iOS compile | `./gradlew :homebase-api:compileKotlinIosSimulatorArm64` | `BUILD SUCCESSFUL` |

## Scope
**In scope (only files to modify/create):**
- `homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt` — swap the generator and import.
- `homebase-api/src/commonTest/kotlin/id/homebase/api/crypto/ByteArrayUtilRandomTest.kt` — NEW regression test.
- `plans/README.md` — index row (create if absent).

**Out of scope (do NOT touch):**
- `homebase-api/.../client/KeyHeader.kt` — call site; fix is centralized in `getRndByteArray`, no edit needed.
- `homebase-api/.../crypto/AesGcm.kt` — call site; same reason.
- `homebase-api/.../client/CryptoHelper.kt` — call site; same reason.
- `homebase-api/.../crypto/ByteArrayUtil.kt::isStrongKey` — unused dead code; do not delete or wire up here (see Maintenance notes).
- `homebase-api/.../crypto/ByteArrayUtilTest.kt` — existing test; leave untouched (the new file is separate per the spec).
- Any Gradle/version-catalog file — the dependency already exists; do not add or bump anything.

## Steps

### Step 1 — Replace the weak generator with the CSPRNG
In `homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt`:

1a. Change the import on line 3 from
```kotlin
import kotlin.random.Random
```
to
```kotlin
import dev.whyoleg.cryptography.random.CryptographyRandom
```
(Keep `import kotlin.uuid.Uuid` on line 4 unchanged.)

1b. Change the body of `getRndByteArray` (line 265) from
```kotlin
        return Random.Default.nextBytes(nCount)
```
to
```kotlin
        return CryptographyRandom.nextBytes(nCount)
```
Leave the KDoc ("Generates a cryptographically safe array of random bytes") as-is — it is now accurate.

> Note: after this edit `Random` is no longer referenced anywhere in the file (verify in Step 1 check). If a stray `Random.` reference remains, the compile in 1c will fail with an unresolved reference — that is the signal to find and convert it.

1c. Verify: `grep -n "Random.Default\|import kotlin.random.Random" homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt` -> **no output**.
Verify: `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

### Step 2 — Add the regression test
Create `homebase-api/src/commonTest/kotlin/id/homebase/api/crypto/ByteArrayUtilRandomTest.kt` with exactly:

```kotlin
package id.homebase.api.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression tests pinning [ByteArrayUtil.getRndByteArray] to a CSPRNG (plan 001).
 *
 * These do not (and cannot, from a unit test) prove a generator is a CSPRNG; they pin the
 * observable contract — correct length, non-repeating output, high byte entropy — so that any
 * future regression back to a trivial/constant generator fails the build.
 */
class ByteArrayUtilRandomTest {

    @Test
    fun getRndByteArray_returnsRequestedLength() {
        for (n in intArrayOf(0, 1, 12, 16, 32, 128)) {
            assertEquals(n, ByteArrayUtil.getRndByteArray(n).size)
        }
    }

    @Test
    fun getRndByteArray_twoCallsDiffer() {
        val a = ByteArrayUtil.getRndByteArray(32)
        val b = ByteArrayUtil.getRndByteArray(32)
        // Collision probability for 32 random bytes is ~2^-256; a match means a broken generator.
        assertNotEquals(a.contentToString(), b.contentToString())
    }

    @Test
    fun getRndByteArray_highDistinctByteRatio() {
        // 1024 bytes from a healthy generator should cover almost all 256 byte values.
        // A constant or low-entropy generator yields a handful of distinct values and fails here.
        val bytes = ByteArrayUtil.getRndByteArray(1024)
        val distinct = bytes.toSet().size
        assertTrue(
            distinct > 200,
            "Expected a high distinct-byte ratio from a CSPRNG, got $distinct distinct of 256",
        )
    }
}
```

2a. Verify: `./gradlew :homebase-api:jvmTest --rerun-tasks` -> `BUILD SUCCESSFUL` with the 3 new tests passing (and the pre-existing `ByteArrayUtilTest` still green).

### Step 3 — Update the plans index
If `plans/README.md` does not exist, create it with a header and a table:
```markdown
# Implementation plans

| Plan | Title | Priority | Status |
|---|---|---|---|
| [001](001-crypto-csprng.md) | Route all crypto random bytes through a CSPRNG | P1 | Done |
```
If it already exists, add/update the `001` row to mark it Done.

3a. Verify: `git status --porcelain` -> shows only the four in-scope paths (ByteArrayUtil.kt, ByteArrayUtilRandomTest.kt, plans/001-crypto-csprng.md, plans/README.md).

## Test plan
- New file: `homebase-api/src/commonTest/kotlin/id/homebase/api/crypto/ByteArrayUtilRandomTest.kt`, modeled after `ByteArrayUtilTest.kt`.
- Cases:
  1. `getRndByteArray_returnsRequestedLength` — length is exactly the requested count across 0/1/12/16/32/128 (12 = GCM nonce, 16 = CBC IV/AES key — the real consumer sizes).
  2. `getRndByteArray_twoCallsDiffer` — the regression this fixes: two successive 32-byte draws must differ (a fixed-seed/constant generator would collide).
  3. `getRndByteArray_highDistinctByteRatio` — entropy smoke check: 1024 bytes yield >200 distinct values; a degenerate generator fails.
- Verify command: `./gradlew :homebase-api:jvmTest --rerun-tasks` -> `BUILD SUCCESSFUL`.

## Done criteria
- [ ] `grep -n "Random.Default" homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt` -> no output.
- [ ] `grep -n "import kotlin.random.Random" homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt` -> no output.
- [ ] `grep -n "CryptographyRandom" homebase-api/src/commonMain/kotlin/id/homebase/api/crypto/ByteArrayUtil.kt` -> shows the import and the call.
- [ ] `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-api:jvmTest --rerun-tasks` -> `BUILD SUCCESSFUL`, 3 new tests pass, `ByteArrayUtilTest` still passes.
- [ ] `git status --porcelain` lists only: `ByteArrayUtil.kt`, `ByteArrayUtilRandomTest.kt`, `plans/001-crypto-csprng.md`, `plans/README.md`.
- [ ] `plans/README.md` row for 001 marked Done.

## STOP conditions
- Drift: the drift-check diff is non-empty AND the live code no longer matches the line-3 import / line-265 body excerpts above — STOP and report what changed.
- `CryptographyRandom` does not resolve on `:homebase-api:compileKotlinJvm` (i.e., the assumption that `cryptography-random` is already a transitive `commonMain` dependency is false) — STOP; do NOT add a Gradle dependency to "fix" it without confirming the dependency graph, since that is out of scope.
- Any verification command fails twice in a row after a genuine retry — STOP and report.
- The fix appears to require editing any call site (KeyHeader/AesGcm/CryptoHelper) or a Gradle file — STOP; that means the centralization assumption is wrong and the plan needs revisiting.

## Maintenance notes
- **SECURITY-06 follow-up (optional, NOT in this plan):** `ByteArrayUtil.isStrongKey` (lines 231-259) is defined but never called anywhere in the codebase — it is dead code that gives a false sense of validation. A future plan should either wire it in (e.g., assert `isStrongKey` on freshly generated keys in `KeyHeader.newRandom16()` / on imported keys) or delete it. Do NOT change it in this plan.
- **Why no call-site edits:** all randomness flows through the single `getRndByteArray` chokepoint, so a reviewer should confirm no other code path constructs IVs/nonces/keys via `kotlin.random.Random` directly. Quick check: `grep -rn "kotlin.random.Random\|Random.Default" homebase-api/src/commonMain` should ideally surface only non-crypto usages (if any crypto usage appears, it needs the same treatment in a follow-up).
- **Test caveat:** the entropy test (`>200` distinct of 256 over 1024 bytes) is a statistical smoke check, not a CSPRNG proof. It is intentionally generous to avoid flakiness; do not tighten the threshold without measuring the false-failure rate.
- A reviewer should scrutinize that `CryptographyRandom.nextBytes` is the blocking/secure overload (it draws from the platform OS CSPRNG on each target) and that no platform `actual` shim is required — it ships as common API in the `cryptography-random` artifact already used by `DatabaseKeyManager`.
