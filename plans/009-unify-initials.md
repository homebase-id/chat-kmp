# Plan 009: Unify four duplicated, surrogate-unsafe initials() implementations into one code-point-aware helper

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/util/StringExtensions.kt homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/OwnerSession.kt homebase-api/src/commonMain/kotlin/id/homebase/api/client/identity/PublicIdentity.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/contact/ContactName.kt homebase-common/src/commonMain/kotlin/id/homebase/core/util/StringExtensions.kt`. If any in-scope file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P2
- Effort: S
- Risk: LOW
- Depends on: none
- Category: tech-debt
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
The avatar-initials algorithm (first-of-first-token + first-of-last-token, split on whitespace) is reimplemented **four times** across three modules, and every copy uses surrogate-unsafe `first()` / `firstOrNull()` on user-controlled display names. CLAUDE.md explicitly forbids this: a name like `"😀 Bob"` stores `😀` as a UTF-16 surrogate pair, so `token.first()` returns a **lone high surrogate**, which renders as a broken `□`/`?` glyph in the avatar and can corrupt downstream serialization. Collapsing the four copies onto one code-point-aware helper fixes the rendering bug everywhere at once and removes ~90 lines of drift-prone duplication. The same `homebase-api/util/StringExtensions.kt` already hosts `truncateToCodePoints` and a surrogate-aware `codePointToString`, so the canonical helper has a natural home there.

## Current state

All five files were read at commit 45e2832e; excerpts below are verbatim with real line numbers.

### 1. `homebase-api/src/commonMain/kotlin/id/homebase/api/util/StringExtensions.kt` (the helper's new home — lowest layer)
Already surrogate-aware utilities live here. `truncateToCodePoints` at lines 71-84 shows the codebase's surrogate-pair idiom:
```kotlin
71	fun String.truncateToCodePoints(maxVisibleCharacters: Int): String {
72	    if (maxVisibleCharacters <= 0) return ""
73	    var codePointCount = 0
74	    var charIndex = 0
75	    while (charIndex < length && codePointCount < maxVisibleCharacters) {
76	        if (charIndex + 1 < length && this[charIndex].isHighSurrogate() && this[charIndex + 1].isLowSurrogate()) {
77	            charIndex += 2
78	        } else {
79	            charIndex += 1
80	        }
81	        codePointCount += 1
82	    }
83	    return substring(0, charIndex)
84	}
```
There is currently **no** `initials`-related function in this file. The new helper is appended here.

### 2. `homebase-common/src/commonMain/kotlin/id/homebase/core/util/StringExtensions.kt` lines 153-169 — canonical `String.initials()`
```kotlin
153	fun String.initials(): String {
154	    val tokens =
155	        this
156	            .trim()
157	            .split("\\s+".toRegex())
158	            .filter { it.isNotEmpty() }
159	
160	    return when {
161	        tokens.size >= 2 ->
162	            "${tokens.first().first()}${tokens.last().first()}".uppercase().trim()
163	
164	        tokens.size == 1 ->
165	            tokens.first().first().uppercaseChar().toString().trim()
166	
167	        else -> ""
168	    }
169	}
```
Fallback for empty input: `""`. Uses surrogate-unsafe `.first()` (the `Char` overload) twice.

### 3. `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/contact/ContactName.kt` lines 13-51 — `ContactName.initials()`
```kotlin
13	    fun initials(): String {
14	        val first =
15	            givenName
16	                ?.trim()
17	                ?.takeIf { it.isNotEmpty() }
18	                ?.firstOrNull()
19	
20	        val last =
21	            surname
22	                ?.trim()
23	                ?.takeIf { it.isNotEmpty() }
24	                ?.firstOrNull()
25	
26	        if (first != null && last != null) {
27	            return "${first}${last}".uppercase()
28	        }
29	
30	        // Fallback: try display name tokens
31	        if (displayName == null) {
32	            return "?"
33	        }
34	
35	        val tokens =
36	            displayName
37	                .trim()
38	                .split("\\s+".toRegex())
39	                .filter { it.isNotEmpty() }
40	
41	        return when {
42	            tokens.size >= 2 ->
43	                "${tokens.first().first()}${tokens.last().first()}".uppercase()
44	
45	            tokens.size == 1 ->
46	                tokens.first().first().uppercaseChar().toString()
47	
48	            else ->
49	                "?"
50	        }
51	    }
```
Priority: `givenName`+`surname` first, then `displayName` tokens; final fallback `"?"`. Uses surrogate-unsafe `firstOrNull()` (Char) on `givenName`/`surname` and `.first()` on tokens.

### 4. `homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/OwnerSession.kt` lines 19-53 — `OwnerSession.initials()`
```kotlin
19	fun OwnerSession.initials(): String {
20	    val first =
21	        firstName
22	            ?.trim()
23	            ?.takeIf { it.isNotEmpty() }
24	            ?.firstOrNull()
25	
26	    val last =
27	        surName
28	            ?.trim()
29	            ?.takeIf { it.isNotEmpty() }
30	            ?.firstOrNull()
31	
32	    if (first != null && last != null) {
33	        return "${first}${last}".uppercase()
34	    }
35	
36	    val tokens =
37	        displayName
38	            ?.trim()
39	            ?.split("\\s+".toRegex())
40	            ?.filter { it.isNotEmpty() }
41	            ?: emptyList()
42	
43	    return when {
44	        tokens.size >= 2 ->
45	            "${tokens.first().first()}${tokens.last().first()}".uppercase()
46	
47	        tokens.size == 1 ->
48	            tokens.first().first().uppercaseChar().toString()
49	
50	        else ->
51	            "?"
52	    }
53	}
```
Priority: `firstName`+`surName` first, then `displayName` tokens; final fallback `"?"`. Note the field is `surName` (capital N), unlike ContactName's `surname`.

### 5. `homebase-api/src/commonMain/kotlin/id/homebase/api/client/identity/PublicIdentity.kt` lines 15-34 — `PublicIdentity.initials()`
```kotlin
15	fun PublicIdentity.initials(): String {
16	    val first = firstName?.trim()?.takeIf { it.isNotEmpty() }?.firstOrNull()
17	    val last = surName?.trim()?.takeIf { it.isNotEmpty() }?.firstOrNull()
18	
19	    if (first != null && last != null) {
20	        return "${first}${last}".uppercase()
21	    }
22	
23	    val tokens = displayName
24	        ?.trim()
25	        ?.split("\\s+".toRegex())
26	        ?.filter { it.isNotEmpty() }
27	        ?: emptyList()
28	
29	    return when {
30	        tokens.size >= 2 -> "${tokens.first().first()}${tokens.last().first()}".uppercase()
31	        tokens.size == 1 -> tokens.first().first().uppercaseChar().toString()
32	        else -> odinId.domainName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
33	    }
34	}
```
Priority: `firstName`+`surName` first, then `displayName` tokens; **distinct final fallback** = first char of `odinId.domainName`, else `"?"`. (Domain names are ASCII per spec, so `domainName.firstOrNull()` is acceptable to leave as-is, but the executor will route it through the helper too for consistency.)

### Dependency direction (confirmed)
- `homebase-chat/build.gradle.kts:51` → `api(project(":homebase-api"))`
- `homebase-common/build.gradle.kts:84` → `api(project(":homebase-api"))`
- `homebase-api/build.gradle.kts` has **no** project dependencies (grep for `homebase-common|homebase-chat|homebase-core|projects.` returns nothing).

So a helper in `id.homebase.api.util` is importable by all four callers. `homebase-api` is the only layer all four reach. **Do not** put the helper in `homebase-common` — `homebase-api` cannot depend on it.

### Convention to apply
Surrogate-safe truncation idiom already in this repo: `truncateToCodePoints` (excerpt above) advances `charIndex += 2` when `isHighSurrogate() && isLowSurrogate()`. The new helper must take the **first whole code point** of a token the same way (2 chars if a surrogate pair, else 1), then `.uppercase()`. Exemplar test file to model the new test after: `homebase-api/src/jvmTest/kotlin/id/homebase/api/util/DecodeHtmlEntitiesTest.kt` (uses `kotlin.test`, `assertEquals`; already includes an emoji surrogate case at line 49-51).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check | `git diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/util/StringExtensions.kt homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/OwnerSession.kt homebase-api/src/commonMain/kotlin/id/homebase/api/client/identity/PublicIdentity.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/contact/ContactName.kt homebase-common/src/commonMain/kotlin/id/homebase/core/util/StringExtensions.kt` | No output (no drift) |
| Compile api (JVM) | `./gradlew :homebase-api:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile chat (JVM) | `./gradlew :homebase-chat:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile common (JVM) | `./gradlew :homebase-common:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Run api tests (incl. new) | `./gradlew :homebase-api:jvmTest` | `BUILD SUCCESSFUL`; new test class passes |
| Combined gate (final) | `./gradlew :homebase-api:jvmTest :homebase-chat:compileKotlinJvm :homebase-common:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Confirm no stray `.first()` initials left | `grep -rn "tokens.first().first()\|tokens.last().first()" homebase-api homebase-common homebase-chat --include="*.kt"` | No output |

## Scope
**In scope (only these files may change):**
- `homebase-api/src/commonMain/kotlin/id/homebase/api/util/StringExtensions.kt` — add the canonical `firstLastInitials` helper.
- `homebase-common/src/commonMain/kotlin/id/homebase/core/util/StringExtensions.kt` — rewrite `String.initials()` body to delegate.
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/contact/ContactName.kt` — rewrite `initials()` to delegate.
- `homebase-api/src/commonMain/kotlin/id/homebase/api/client/auth/OwnerSession.kt` — rewrite `initials()` to delegate.
- `homebase-api/src/commonMain/kotlin/id/homebase/api/client/identity/PublicIdentity.kt` — rewrite `initials()` to delegate.
- `homebase-api/src/commonTest/kotlin/id/homebase/api/util/InitialsTest.kt` — NEW test file.
- `plans/README.md` — add/update this plan's row (final step).

**Out of scope (do NOT touch):**
- Any avatar-rendering composable (`ReactionsBottomSheet.kt`, `InAppNotificationBanner.kt`, `MessageBubble.kt`, `ConversationListPane.kt`, `MessageSearchItem.kt`, `ConversationContent.kt`, `ConnectRequestBottomSheet.kt`, `GroupSettingsViewModel.kt`, `EventDetailDialog.kt`, `DriveContactService.kt`, `SettingsScreen.kt`, `MomentsScreen.kt`, `ConnectionsScreen.kt`, `MomentsRecipientLookupService.kt`) — they only **call** `.initials()`; their signatures/return contracts are unchanged, so no edits are needed. Changing them is out of scope and risks UI regressions.
- The signature, parameter names, and **fallback strings** of each public `initials()` function — preserve them exactly (`""` for `String.initials()`, `"?"` for ContactName/OwnerSession, domain-first-char-then-`"?"` for PublicIdentity). The helper only changes how the *first code point of a token* is extracted, never the fallback policy.
- `truncateToCodePoints` / `codePointToString` / `decodeHtmlEntities` and other functions in the api StringExtensions file — leave untouched.

## Steps

### Step 1 — Add the canonical helper to api StringExtensions
In `homebase-api/src/commonMain/kotlin/id/homebase/api/util/StringExtensions.kt`, append the following at the end of the file (after `cleanDomain`, after line 181/182). The helper returns the first whole code point of `first` plus the first whole code point of `last`, each uppercased; either may be blank/null. It never throws and never returns a half surrogate.

```kotlin
/**
 * Surrogate-safe avatar initials from a first token and a last token.
 *
 * Returns the first **whole** Unicode code point of [first] concatenated with the
 * first whole code point of [last], uppercased. A code point may be a 2-char
 * UTF-16 surrogate pair (e.g. an emoji); this advances past the pair instead of
 * splitting it, so an emoji-leading name never yields a lone surrogate.
 *
 * Tokens are trimmed; a blank/null token contributes nothing. With both tokens
 * present you get a 2-glyph result; with one, a 1-glyph result; with neither, "".
 * Callers own their own empty-input fallback (e.g. "?" or a domain char).
 */
fun firstLastInitials(first: String?, last: String?): String =
    buildString {
        append(firstCodePointUpper(first))
        append(firstCodePointUpper(last))
    }

private fun firstCodePointUpper(token: String?): String {
    val t = token?.trim().orEmpty()
    if (t.isEmpty()) return ""
    val end = if (t.length >= 2 && t[0].isHighSurrogate() && t[1].isLowSurrogate()) 2 else 1
    return t.substring(0, end).uppercase()
}
```

Rationale: mirrors the `isHighSurrogate() && isLowSurrogate()` advance used by `truncateToCodePoints` directly above; `.uppercase()` on a `String` (not `uppercaseChar()` on a `Char`) so a surrogate-pair code point uppercases correctly and no `Char` API is touched.

- Verify: `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

### Step 2 — Delegate `OwnerSession.initials()` (same module, lowest risk first)
Replace the body of `OwnerSession.initials()` (lines 19-53) so the firstName/surName branch and the displayName-token branch both route through `firstLastInitials`, preserving the `"?"` final fallback. Add the import.

New body:
```kotlin
fun OwnerSession.initials(): String {
    val structured = firstLastInitials(firstName, surName)
    if (structured.isNotEmpty()) return structured

    val tokens = displayName
        ?.trim()
        ?.split("\\s+".toRegex())
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    return when {
        tokens.size >= 2 -> firstLastInitials(tokens.first(), tokens.last())
        tokens.size == 1 -> firstLastInitials(tokens.first(), null)
        else -> "?"
    }
}
```
Add at the top of the file, after `import id.homebase.api.common.OdinId` (line 4):
```kotlin
import id.homebase.api.util.firstLastInitials
```
Note the behavior preservation: previously the structured branch only fired when **both** `firstName` and `surName` were non-blank. `firstLastInitials(firstName, surName)` returns a 1-glyph string if only one is present, which would now short-circuit the displayName fallback. To preserve the original "needs both" semantics, gate it: only use the structured result when **both** inputs are non-blank.

Corrected body (use this one):
```kotlin
fun OwnerSession.initials(): String {
    if (!firstName.isNullOrBlank() && !surName.isNullOrBlank()) {
        return firstLastInitials(firstName, surName)
    }

    val tokens = displayName
        ?.trim()
        ?.split("\\s+".toRegex())
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    return when {
        tokens.size >= 2 -> firstLastInitials(tokens.first(), tokens.last())
        tokens.size == 1 -> firstLastInitials(tokens.first(), null)
        else -> "?"
    }
}
```

- Verify: `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

### Step 3 — Delegate `PublicIdentity.initials()` (same module)
Replace the body of `PublicIdentity.initials()` (lines 15-34), preserving its distinct domain-first-char fallback. Add the import after line 4.

```kotlin
fun PublicIdentity.initials(): String {
    if (!firstName.isNullOrBlank() && !surName.isNullOrBlank()) {
        return firstLastInitials(firstName, surName)
    }

    val tokens = displayName
        ?.trim()
        ?.split("\\s+".toRegex())
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    return when {
        tokens.size >= 2 -> firstLastInitials(tokens.first(), tokens.last())
        tokens.size == 1 -> firstLastInitials(tokens.first(), null)
        else -> firstLastInitials(odinId.domainName, null).ifEmpty { "?" }
    }
}
```
Add after `import id.homebase.api.common.OdinId` (line 4):
```kotlin
import id.homebase.api.util.firstLastInitials
```
(`firstLastInitials(odinId.domainName, null)` returns the first code point of the domain uppercased — equivalent to the old `domainName.firstOrNull()?.uppercaseChar()` for ASCII domains and surrogate-safe regardless. `.ifEmpty { "?" }` reproduces the `?: "?"` final fallback.)

- Verify: `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

### Step 4 — Delegate `ContactName.initials()` (homebase-chat)
Replace the body of `ContactName.initials()` (lines 13-51). Field is `givenName`/`surname` (lowercase n). Final fallback `"?"`; note original returns `"?"` early when `displayName == null` after the structured branch fails — `emptyList()` + the `else -> "?"` branch reproduces that, so the early `displayName == null` guard is no longer needed.

```kotlin
    fun initials(): String {
        if (!givenName.isNullOrBlank() && !surname.isNullOrBlank()) {
            return firstLastInitials(givenName, surname)
        }

        val tokens = displayName
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        return when {
            tokens.size >= 2 -> firstLastInitials(tokens.first(), tokens.last())
            tokens.size == 1 -> firstLastInitials(tokens.first(), null)
            else -> "?"
        }
    }
```
Add the import at the top of the file (after line 1 `package ...`, alongside the existing `import kotlinx.serialization.Serializable`):
```kotlin
import id.homebase.api.util.firstLastInitials
```

- Verify: `./gradlew :homebase-chat:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

### Step 5 — Delegate `String.initials()` (homebase-common canonical)
Replace the body of `String.initials()` (lines 153-169) in `homebase-common/.../core/util/StringExtensions.kt`. Preserve the `""` empty fallback. Add the import.

```kotlin
fun String.initials(): String {
    val tokens =
        this
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }

    return when {
        tokens.size >= 2 -> firstLastInitials(tokens.first(), tokens.last())
        tokens.size == 1 -> firstLastInitials(tokens.first(), null)
        else -> ""
    }
}
```
Add the import after `package id.homebase.core.util` (line 3):
```kotlin
import id.homebase.api.util.firstLastInitials
```

- Verify: `./gradlew :homebase-common:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

### Step 6 — Add the regression test
Create `homebase-api/src/commonTest/kotlin/id/homebase/api/util/InitialsTest.kt` (commonTest so it runs on every target; it is exercised by `:homebase-api:jvmTest`). Model after `DecodeHtmlEntitiesTest.kt`.

```kotlin
package id.homebase.api.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FirstLastInitialsTest {

    @Test
    fun two_ascii_tokens() {
        assertEquals("BP", firstLastInitials("Bishwajeet", "Parhi"))
    }

    @Test
    fun single_token_first_only() {
        assertEquals("B", firstLastInitials("bob", null))
        assertEquals("B", firstLastInitials("bob", "   "))
    }

    @Test
    fun accented_first_char_uppercases() {
        // 'é' is a single BMP code point; must uppercase to 'É', not be dropped.
        assertEquals("ÉL", firstLastInitials("élise", "lacroix"))
    }

    @Test
    fun emoji_leading_token_keeps_whole_surrogate_pair() {
        // "😀" is U+1F600, a UTF-16 surrogate pair. The result must contain the
        // WHOLE emoji + the next token's initial, never a lone high surrogate.
        val result = firstLastInitials("😀face", "Bob")
        assertEquals("😀B", result)
        // No unpaired surrogate left behind.
        assertEquals(2, result.codePointCount(0, result.length))
    }

    @Test
    fun emoji_only_single_token() {
        val result = firstLastInitials("😀", null)
        assertEquals("😀", result)
        assertEquals(1, result.codePointCount(0, result.length))
    }

    @Test
    fun both_blank_returns_empty() {
        assertEquals("", firstLastInitials(null, null))
        assertEquals("", firstLastInitials("  ", ""))
    }
}
```
Note: `String.codePointCount(beginIndex, endIndex)` is a JVM-only `java.lang.String` method. Since this test runs under `:homebase-api:jvmTest`, it is available there. If a future executor moves these assertions to a non-JVM target, replace the surrogate-count assertions with `assertEquals(2, result.length)` (the emoji result is exactly 2 chars: 2 surrogates + 1 BMP char... — re-derive per case) or guard the code-point assert behind an `expect`/`actual`. For the JVM gate in this plan, `codePointCount` is correct and clearest.

- Verify: `./gradlew :homebase-api:jvmTest` -> `BUILD SUCCESSFUL`, `FirstLastInitialsTest` passes (6 tests).

### Step 7 — Final combined gate + grep sweep
- Verify: `./gradlew :homebase-api:jvmTest :homebase-chat:compileKotlinJvm :homebase-common:compileKotlinJvm` -> `BUILD SUCCESSFUL`.
- Verify: `grep -rn "tokens.first().first()\|tokens.last().first()" homebase-api homebase-common homebase-chat --include="*.kt"` -> no output (all `.first().first()` patterns removed).

### Step 8 — Update plans/README.md
Add (or update) the row for this plan in `plans/README.md` (create the file with a header table if it does not yet exist): `009 | Unify initials() helpers | tech-debt | DONE`.

## Test plan
- New file: `homebase-api/src/commonTest/kotlin/id/homebase/api/util/InitialsTest.kt`, class `FirstLastInitialsTest`.
- Cases (the emoji case is the regression this plan fixes):
  1. `two_ascii_tokens` — `"Bishwajeet","Parhi"` → `"BP"`.
  2. `single_token_first_only` — `"bob",null` and `"bob","   "` → `"B"` (blank last contributes nothing).
  3. `accented_first_char_uppercases` — `"élise","lacroix"` → `"ÉL"` (BMP accent survives + uppercases).
  4. `emoji_leading_token_keeps_whole_surrogate_pair` — **regression**: `"😀face","Bob"` → `"😀B"` with code-point count 2 (no half surrogate).
  5. `emoji_only_single_token` — `"😀",null` → `"😀"` (whole pair, count 1).
  6. `both_blank_returns_empty` — `null,null` and `"  ",""` → `""`.
- Model after: `homebase-api/src/jvmTest/kotlin/id/homebase/api/util/DecodeHtmlEntitiesTest.kt` (same `kotlin.test` imports, same `@Test`/`assertEquals` style, already has an emoji surrogate assertion).
- Verify command: `./gradlew :homebase-api:jvmTest`.

## Done criteria
- [ ] `git diff --stat 45e2832e..HEAD -- <in-scope paths>` at plan-write time shows no drift (Step 0).
- [ ] `firstLastInitials` exists exactly once: `grep -rn "fun firstLastInitials" homebase-api homebase-common homebase-chat --include="*.kt"` returns exactly 1 line (in api StringExtensions).
- [ ] `grep -rn "tokens.first().first()\|tokens.last().first()" homebase-api homebase-common homebase-chat --include="*.kt"` returns nothing.
- [ ] `./gradlew :homebase-api:jvmTest` → `BUILD SUCCESSFUL`, `FirstLastInitialsTest` 6 tests pass.
- [ ] `./gradlew :homebase-api:jvmTest :homebase-chat:compileKotlinJvm :homebase-common:compileKotlinJvm` → `BUILD SUCCESSFUL`.
- [ ] `git status --porcelain` shows ONLY the 6 in-scope source/test files plus `plans/README.md` and `plans/009-unify-initials.md`.
- [ ] `plans/README.md` row for plan 009 updated to DONE.

## STOP conditions
- Drift: any in-scope file differs from the Current-state excerpts above and you cannot map the change 1:1 — STOP and report.
- Any verification command fails twice in a row after a genuine fix attempt — STOP and report the failure output.
- The fix appears to require editing a file outside Scope (e.g. an avatar composable, a build.gradle, or `truncateToCodePoints`) — STOP; the delegation should be source-compatible with zero caller changes, so a required out-of-scope edit means an assumption broke.
- Assumption check — if `homebase-api/build.gradle.kts` turns out to depend on `homebase-common` (it must NOT), or if `homebase-chat`/`homebase-common` do NOT depend on `homebase-api`, the helper placement is wrong — STOP and re-derive the lowest common module.
- If `String.codePointCount` is unavailable when compiling the test (e.g. the test was moved out of jvm), do NOT delete the surrogate assertion to make it pass — switch to a `.length`/code-point-derived assertion as noted in Step 6.

## Maintenance notes
- **Behavior subtlety preserved:** the three "structured name" callers (ContactName/OwnerSession/PublicIdentity) originally only used the firstName+surname branch when **both** fields were non-blank. The naive `if (firstLastInitials(a,b).isNotEmpty())` would fire on just one field and skip the displayName fallback — this plan gates each on `!a.isNullOrBlank() && !b.isNullOrBlank()` to keep the old precedence. A reviewer should confirm that gate in all three.
- **Distinct fallbacks are intentional and must stay:** `String.initials()` → `""`; ContactName/OwnerSession → `"?"`; PublicIdentity → domain-first-char then `"?"`. The shared helper deliberately does NOT bake in a fallback; it returns `""` for empty input so each caller owns its policy. Do not "simplify" by pushing a fallback into `firstLastInitials`.
- **Field naming trap:** `OwnerSession`/`PublicIdentity` use `surName` (capital N); `ContactName` uses `surname`. Don't copy-paste the wrong field name between callers.
- **Why api, not common:** `homebase-api` is the lowest layer; `homebase-common`/`homebase-chat` depend on it but not vice versa. If a future refactor needs initials in `webApp`-only code, the helper is already reachable (api is exported transitively).
- **Deferred follow-up (out of scope here):** `String.initials()` in homebase-common and the per-type `initials()` could eventually converge to a single extension taking `(first, last, displayName, fallback)`, but that would change call sites in ~14 files and is not worth the churn now. Leave the four entry points; they share the one helper.
- This plan does NOT touch avatar composables; if avatars still render a broken glyph after this, the bug is in the rendering/measurement path, not initials — investigate there separately.
