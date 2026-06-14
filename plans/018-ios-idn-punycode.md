# Plan 018: Give iOS real (or uniformly-failing) IDN handling instead of a crash

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-api/src/nativeMain/kotlin/id/homebase/api/common/Idn.kt homebase-api/src/commonMain/kotlin/id/homebase/api/common/AsciiDomainName.kt`. On mismatch with the Current state excerpts below, STOP.

## Status
- Priority: **P3**
- Effort: **M**
- Risk: **LOW** (dormant today — see Why)
- Depends on: none
- Category: **bug** (cross-platform parity)
- Planned at: commit `45e2832e`, 2026-06-14
- Drift at planning time: **NONE.** HEAD == `45e2832e`; the cited code matches byte-for-byte (codeMatchedFinding = true).

## Why this matters

On iOS/Native, `Idn.toAscii` **throws `IllegalArgumentException` for any input containing a code point > 127** (`homebase-api/src/nativeMain/.../common/Idn.kt:7-12`), while Android and Desktop use the real `java.net.IDN.toASCII` Punycode encoder. The only function that reaches `Idn.toAscii` is `AsciiDomainName.fromIDN()` (`AsciiDomainName.kt:42`), and an audit confirms **`fromIDN` has zero production callers today** — `OdinId` is constructed through the validating `AsciiDomainName(identifier)` operator (`OdinId.kt:84`), which never calls `toAscii`. So the crash is **dormant**.

The cost is a latent platform divergence: the moment any feature accepts a *raw IDN* identity string (e.g. a future "add contact by unicode domain" or a server payload carrying a non-ASCII identity) and routes it through `fromIDN`, the app will work on Android/Desktop and **crash only on iOS** with an opaque message — exactly the class of bug that escapes JVM-only CI. This plan removes the divergence: either iOS gets a real RFC 3492 Punycode encoder that matches `java.net.IDN`, or — at minimum — `fromIDN` fails **uniformly** with the same typed error on every platform, documented as unsupported. Either outcome makes the behaviour predictable and testable instead of a platform-specific landmine.

## Current state

The `expect` declaration is **not** in a standalone file — it is declared inside `AsciiDomainName.kt`:

`homebase-api/src/commonMain/kotlin/id/homebase/api/common/AsciiDomainName.kt`
```kotlin
8   expect object Idn {
9       fun toAscii(idn: String): String
10      fun toUnicode(puny: String): String
11  }
...
22      fun toIDN(): String = Idn.toUnicode(domainName)
...
41          fun fromIDN(idnDomainName: String): AsciiDomainName {
42              val puny = Idn.toAscii(idnDomainName)
43              return AsciiDomainName(puny)          // calls the operator invoke above
44          }
```
(The validating constructor at lines 32-36 lowercases + calls `AsciiDomainNameValidator.assertValidDomain`. `fromIDN` is the **only** caller of `Idn.toAscii`; `toIDN`/`Idn.toUnicode` is unused too but is OUT of scope — see Scope.)

`homebase-api/src/nativeMain/kotlin/id/homebase/api/common/Idn.kt` (the file to change — full current contents):
```kotlin
1   package id.homebase.api.common
2
3   actual object Idn {
4       actual fun toAscii(idn: String): String {
5           // Strict: most domains are ASCII anyway. If you ever need real IDN on iOS,
6           // add a pure-Kotlin Punycode library later (none are widely used yet).
7           if (idn.any { it.code > 127 }) {
8               throw IllegalArgumentException(
9                   "Non-ASCII (IDN) domains are not supported on Native targets yet. " +
10                          "Use only ASCII domains or run on JVM/Android."
11              )
12          }
13          return idn.lowercase()
14      }
15
16      actual fun toUnicode(puny: String): String = puny   // already ASCII
17  }
```

JVM and Android actuals (the oracle behaviour to match — identical in both files):

`homebase-api/src/jvmMain/.../common/Idn.kt:4-5` and `homebase-api/src/androidMain/.../common/Idn.kt:4-5`
```kotlin
actual fun toAscii(idn: String): String =
    java.net.IDN.toASCII(idn, java.net.IDN.USE_STD3_ASCII_RULES)
```

wasmJs actual (combined into a different file — note the filename):
`homebase-api/src/wasmJsMain/kotlin/id/homebase/api/common/AsciiDomainName.web.kt:7-10`
```kotlin
actual object Idn {
    actual fun toAscii(idn: String): String = idn
    actual fun toUnicode(puny: String): String = puny
}
```
(wasmJs is a pass-through stub and is OUT of scope for the *encoder* option; if you take the **minimum** option you will touch the shared `fromIDN` contract, which affects all platforms — see Step 4.)

Caller audit (run yourself in Step 0 to re-confirm before editing):
- `fromIDN` callers across the repo: only its own definition at `AsciiDomainName.kt:41`.
- `Idn.toAscii` callers: only `AsciiDomainName.kt:42`.
- `AsciiDomainName` is referenced in `OdinId.kt` and `commonTest/.../OdinIdCacheTest.kt` only.

**Test source-set layout** (homebase-api): `commonTest`, `jvmTest`, `nativeTest`, `wasmJsTest`, `jvmAndNativeTest`, `androidHostTest`, `androidDeviceTest`. Existing exemplar test to model style/imports after: `homebase-api/src/commonTest/kotlin/id/homebase/api/common/OdinIdCacheTest.kt` (uses `kotlin.test.Test` / `assertEquals` / `assertTrue`).

**Convention reminders:** pure Kotlin only (no `java.*` in commonMain/nativeMain); tests use `kotlin.test` + fakes, never Mockito/MockK; this module/package has no Compose UI, so the Konsist string-literal rule does not apply here.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check | `git diff --stat 45e2832e..HEAD -- homebase-api/src/nativeMain/kotlin/id/homebase/api/common/Idn.kt homebase-api/src/commonMain/kotlin/id/homebase/api/common/AsciiDomainName.kt` | empty output (no drift) |
| Re-confirm callers | `grep -rn "fromIDN\|Idn.toAscii" --include="*.kt" homebase-api/src \| grep -v /build/` | only `AsciiDomainName.kt:41/42` |
| iOS compile (macOS host only) | `./gradlew :homebase-api:compileKotlinIosSimulatorArm64` | `BUILD SUCCESSFUL` |
| JVM compile | `./gradlew :homebase-api:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| JVM tests | `./gradlew :homebase-api:jvmTest` | `BUILD SUCCESSFUL`; new tests green |
| iOS tests (encoder option, macOS host only) | `./gradlew :homebase-api:iosSimulatorArm64Test` | `BUILD SUCCESSFUL`; new native tests green |
| wasmJs compile (only if minimum option touches shared contract) | `./gradlew :homebase-api:compileKotlinWasmJs` | `BUILD SUCCESSFUL` |

> If you are NOT on a macOS host, the iOS compile/test commands cannot run. Do the JVM gates, mark the iOS gates as "deferred to CI", and note it in your completion report — do not claim iOS verified.

## Scope

**Decision gate (do this first):** choose ONE option and record it in your final report.
- **Option A (preferred):** implement a pure-Kotlin RFC 3492 Punycode `toASCII` in the native actual so iOS matches `java.net.IDN`. Effort M. Take this if you can run the iOS test gate (macOS host) and budget allows ~100-150 lines + tests.
- **Option B (minimum acceptable):** make `fromIDN` fail **uniformly** across all platforms with one typed error, and document IDN as unsupported pending the encoder. Take this only if the encoder is out of budget or no macOS host is available. Because the crash is dormant, B is acceptable.

**In scope (Option A):**
- `homebase-api/src/nativeMain/kotlin/id/homebase/api/common/Idn.kt` — replace the throwing `toAscii` with a real Punycode encoder.
- `homebase-api/src/commonTest/kotlin/id/homebase/api/common/IdnTest.kt` (NEW) — shared vectors (runs on JVM via `java.net.IDN` oracle behaviour AND on native via the new encoder).

**In scope (Option B):**
- `homebase-api/src/commonMain/kotlin/id/homebase/api/common/AsciiDomainName.kt` — change `fromIDN` so the *unsupported-IDN* failure mode is identical on every platform (do NOT change the validating constructor).
- `homebase-api/src/commonTest/kotlin/id/homebase/api/common/IdnTest.kt` (NEW) — assert the uniform typed error for non-ASCII input; assert ASCII pass-through still works.

**Out of scope (do NOT touch, with reason):**
- `OdinId.kt` — uses the validating constructor, not `fromIDN`; changing it would alter the identity path (the finding explicitly excludes it).
- The `AsciiDomainName` operator `invoke` / `AsciiDomainNameValidator` — the ASCII validation path is correct and shared; do not weaken it.
- `Idn.toUnicode` / `toIDN()` — `toUnicode` is a separate (also-unused) decode path; this plan is about `toAscii` only. Leave the native `toUnicode` pass-through as-is.
- `jvmMain`/`androidMain` `Idn.kt` — already use `java.net.IDN`; they are the oracle, don't change them.
- wasmJs `AsciiDomainName.web.kt` — Option A does not need it (web stub stays); only touch it under Option B **if** the compiler tells you the shared contract change requires it (it should not, since you only change `fromIDN`, not the `expect` signature).

## Steps

### Step 0 — Drift + caller re-confirmation (both options)
Run the Drift check and the caller grep from the Commands table.
- **Verify:** drift output is empty AND grep shows only `AsciiDomainName.kt:41` and `:42`.
- **STOP** if either differs (code moved, a new caller appeared, or `Idn.kt` already changed) — report the mismatch.

### Step 1 — Pick the option and record it
Write down A or B and why (macOS host available? budget?). This drives the rest.
- **Verify:** you have a one-line decision recorded for the final report. No build impact.

---
### Option A steps (preferred — real encoder)

### A2 — Implement RFC 3492 Punycode `toASCII` in the native actual
Replace the body of `toAscii` in `homebase-api/src/nativeMain/kotlin/id/homebase/api/common/Idn.kt`. Requirements (match `java.net.IDN.toASCII` for the test vectors in Step A4):
- Split the input on the IDN label separators `.` (U+002E) — and also `。`, `．`, `｡`, which `java.net.IDN` treats as dots; re-join encoded labels with `.`.
- For each label: if it is all-ASCII, lowercase it and emit as-is (do NOT prefix `xn--`). Otherwise lowercase, run the RFC 3492 encode algorithm, and emit `xn--` + the encoded basic+extended output.
- RFC 3492 constants: base=36, tmin=1, tmax=26, skew=38, damp=700, initial_bias=72, initial_n=128 (0x80). Iterate over **Unicode code points**, not UTF-16 chars — decode surrogate pairs from the Kotlin `String` first (an iOS-relevant correctness point; see CLAUDE.md "Strings & Unicode"). Use `digit_to_basic` mapping `0..25 -> 'a'..'z'`, `26..35 -> '0'..'9'`.
- Keep `toUnicode` unchanged (`actual fun toUnicode(puny: String): String = puny`).
- Pure Kotlin only — **no `java.*`, no platform APIs**. This file is in `nativeMain`.

Sketch of the structure (adapt; do not copy blindly):
```kotlin
package id.homebase.api.common

actual object Idn {
    actual fun toAscii(idn: String): String =
        idn.split('.', '。', '．', '｡')
            .joinToString(".") { encodeLabel(it.lowercase()) }

    actual fun toUnicode(puny: String): String = puny

    private fun encodeLabel(label: String): String {
        if (label.all { it.code < 128 }) return label
        return "xn--" + punycodeEncode(label)
    }

    private fun punycodeEncode(input: String): String { /* RFC 3492 */ }
}
```
- **Verify:** `./gradlew :homebase-api:compileKotlinIosSimulatorArm64` → `BUILD SUCCESSFUL`. (If no macOS host: `./gradlew :homebase-api:compileKotlinJvm` to confirm common still builds, and defer the iOS gate to CI.)

### A3 — (build never broken) confirm JVM/common untouched
You changed only `nativeMain`, so JVM behaviour is unchanged.
- **Verify:** `./gradlew :homebase-api:compileKotlinJvm` → `BUILD SUCCESSFUL`.

### A4 — Add shared vector tests (see Test plan)
Create `homebase-api/src/commonTest/.../common/IdnTest.kt` with the known IDN vectors. These run on BOTH jvm (proving the oracle) and native (proving the new encoder) from one `commonTest` file.
- **Verify (JVM):** `./gradlew :homebase-api:jvmTest` → green.
- **Verify (native, macOS host):** `./gradlew :homebase-api:iosSimulatorArm64Test` → green. If no macOS host, defer this gate to CI and say so.

---
### Option B steps (minimum — uniform failure)

### B2 — Make `fromIDN` fail uniformly
In `homebase-api/src/commonMain/.../common/AsciiDomainName.kt`, change `fromIDN` so that *any non-ASCII input fails the same way on every platform*, instead of relying on the per-platform `Idn.toAscii` (which throws on native but punycodes on JVM). Keep ASCII input working (lowercase + validate via the existing constructor). Example shape:
```kotlin
/**
 * IDN (non-ASCII) domains are NOT YET SUPPORTED on any platform — see plan 018.
 * ASCII input is lowercased and validated; non-ASCII input fails uniformly.
 * TODO(plan-018 Option A): replace with a real Punycode encoder so this accepts
 * unicode identities and matches java.net.IDN across targets.
 */
fun fromIDN(idnDomainName: String): AsciiDomainName {
    require(idnDomainName.all { it.code < 128 }) {
        "IDN (non-ASCII) domains are not supported yet; pass an ASCII/punycode domain."
    }
    return AsciiDomainName(idnDomainName) // lowercases + validates
}
```
Notes: this removes the only call to `Idn.toAscii`, so the per-platform `toAscii` actuals become unused but **must stay** (the `expect`/`actual` signature is unchanged; the native throw is now unreachable from production). Do NOT delete the native throw in Option B — leave it as a documented guard. Do NOT change the `expect` signature, so no platform actual needs editing.
- **Verify (common builds):** `./gradlew :homebase-api:compileKotlinJvm` → `BUILD SUCCESSFUL`.
- **Verify (no platform actual broke):** `./gradlew :homebase-api:compileKotlinWasmJs` → `BUILD SUCCESSFUL` (and `compileKotlinIosSimulatorArm64` on a macOS host).

### B3 — Add uniform-failure tests (see Test plan)
- **Verify:** `./gradlew :homebase-api:jvmTest` → green; the new non-ASCII case asserts the typed failure and the ASCII case asserts success.

## Test plan

New file: `homebase-api/src/commonTest/kotlin/id/homebase/api/common/IdnTest.kt`
Model imports/style after `commonTest/.../OdinIdCacheTest.kt` (`kotlin.test.Test`, `assertEquals`, `assertTrue`, `assertFailsWith`).

**Option A test cases (vectors — `Idn.toAscii(input) == expected`):**
These are canonical RFC 3492 / IDN examples; each is what `java.net.IDN.toASCII(input, USE_STD3_ASCII_RULES)` returns, so the same `assertEquals` passes on JVM (oracle) and native (new encoder):
- `"bücher.ch"` → `"xn--bcher-kva.ch"`
- `"例え.テスト"` → `"xn--r8jz45g.xn--zckzah"`
- `"münchen.de"` → `"xn--mnchen-3ya.de"`
- `"faß.de"` → `"xn--fa-hia.de"`
- ASCII pass-through: `"example.com"` → `"example.com"` (no `xn--`, lowercased)
- mixed-case ASCII: `"EXAMPLE.COM"` → `"example.com"`
- surrogate-pair label containing a non-BMP code point (e.g. an emoji-domain label) encodes without throwing and round-trips against `java.net.IDN` — this is the **regression guard for the iOS-only crash** and the UTF-16 surrogate correctness note in CLAUDE.md. (If you cannot find a stable cross-checked vector, at minimum assert `toAscii` of such input does not throw and starts with `xn--`.)
- Round-trip sanity: for each non-ASCII vector, `assertTrue(result.split('.').all { it.length <= 63 })` (no label exceeds the DNS label cap).

**Option B test cases:**
- `assertFailsWith<IllegalArgumentException> { AsciiDomainName.fromIDN("bücher.ch") }` — non-ASCII fails uniformly (this is the regression: it must fail the SAME on JVM and native, not crash only on iOS).
- `AsciiDomainName.fromIDN("example.com").domainName == "example.com"` — ASCII still works.
- `AsciiDomainName.fromIDN("EXAMPLE.COM").domainName == "example.com"` — lowercased.

**Regression this fixes:** before this plan, `fromIDN("bücher.ch")` punycodes on Android/Desktop but throws `IllegalArgumentException` only on iOS — a silent platform divergence. Option A makes all platforms succeed identically; Option B makes all platforms fail identically. The `commonTest` placement guarantees the assertion runs on native too, so CI (which runs `iosSimulatorArm64Test`) would catch a re-divergence.

**Verify command:** `./gradlew :homebase-api:jvmTest` (always) and, on a macOS host, `./gradlew :homebase-api:iosSimulatorArm64Test`.

## Done criteria

- [ ] Step 0 drift check empty; caller grep shows only `AsciiDomainName.kt:41/42`.
- [ ] Exactly ONE option (A or B) implemented and recorded in the report.
- [ ] (A) `nativeMain/.../Idn.kt` `toAscii` no longer throws for non-ASCII; it returns `xn--`-prefixed punycode matching `java.net.IDN` for the test vectors. OR (B) `fromIDN` rejects non-ASCII with one typed `IllegalArgumentException` on every platform and the native `toAscii` throw is left in place but unreachable from production.
- [ ] `homebase-api/src/commonTest/.../common/IdnTest.kt` exists with the cases for the chosen option, including the divergence-regression case.
- [ ] `./gradlew :homebase-api:compileKotlinJvm` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-api:jvmTest` → `BUILD SUCCESSFUL` with new tests green.
- [ ] On a macOS host: `./gradlew :homebase-api:compileKotlinIosSimulatorArm64` and `:homebase-api:iosSimulatorArm64Test` → `BUILD SUCCESSFUL`. If no macOS host, both deferred-to-CI and stated as such.
- [ ] No file outside Scope changed (`git diff --name-only` lists only the chosen option's in-scope files).
- [ ] `OdinId.kt`, the validating `AsciiDomainName(...)` constructor, and `AsciiDomainNameValidator` are byte-for-byte unchanged.
- [ ] plans/README.md row for 018 updated (or, if `plans/README.md` does not exist, note that in the report — do not create a README solely for this).

## STOP conditions

- Drift check non-empty, or `Idn.kt`/`AsciiDomainName.kt` no longer matches the Current-state excerpts → STOP, report drift.
- A NEW production caller of `fromIDN` or `Idn.toAscii` appears in the Step 0 grep → STOP: a real consumer now exists; Option A becomes mandatory (uniform-fail would break that caller) and the consumer's expectations must be checked before proceeding.
- (Option A) Any test vector disagrees between JVM (`java.net.IDN`) and your native encoder → STOP: do not "fix" by deleting the failing vector or wrapping in try/catch (CLAUDE.md debugging rule). The encoder is wrong; capture the exact input/expected/actual and correct the algorithm (most likely the bias-adaptation loop or surrogate decoding).
- Tempted to change `OdinId.kt`, the validating constructor, or `AsciiDomainNameValidator` to make a test pass → STOP; those are explicitly out of scope.
- No macOS host AND you cannot reach the iOS gates → do NOT claim iOS verified; complete JVM gates, mark iOS deferred-to-CI, and report.

## Maintenance notes

- **Reviewer should scrutinize:** the Punycode bias-adaptation loop (the classic RFC 3492 bug site), code-point iteration over surrogate pairs (UTF-16 hazard called out in CLAUDE.md), and that `xn--` is emitted only for non-ASCII labels (an all-ASCII label must pass through untouched, never double-encoded). Confirm the vectors actually match `java.net.IDN` — generate a couple fresh ones with `java.net.IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES)` in a JVM scratch before trusting hand-typed expected strings.
- **Why `toUnicode` is left alone:** it is a separate decode path, also currently unused, and `java.net.IDN.toUnicode` semantics (it never throws, just passes through unencodable input) are easy to mismatch. A real native `toUnicode` (Punycode *decode*) is a deferred follow-up; track it if/when a feature needs to display unicode identities decoded from `xn--`.
- **If Option B shipped:** the native `toAscii` throw becomes dead-but-documented. The deferred follow-up is Option A (the real encoder) — leave the `TODO(plan-018 Option A)` breadcrumb in `fromIDN` so the next author finds it. Do not delete the platform `toAscii` actuals; the `expect`/`actual` contract still requires them.
- **Konsist note:** this package has no Composables, so the `Text("…")` string-literal rule does not apply; you may use plain string literals in the encoder and tests.
