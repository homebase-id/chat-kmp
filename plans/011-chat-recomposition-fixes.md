# Plan 011: Fix four chat-UI recomposition inefficiencies

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/ConversationListUiState.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/ConversationListScreen.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/MediaDownloadHandler.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationContent.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationListPane.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageItem.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MediaItem.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MediaGallery.kt`. If any in-scope file changed since this plan was written, compare the Current-state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P2
- Effort: M
- Risk: MED  (chat hot path; verify each sub-fix in isolation)
- Depends on: none
- Category: perf
- Planned at: commit 45e2832e, 2026-06-14

These four sub-fixes are independent. **Stage them — one commit per fix — and run the per-fix
gate (compile + jvmTest) after EACH before starting the next.** Do not batch. If any one fix
fails its gate twice, STOP and report; the other three are still landable on their own.

## DRIFT NOTE (read before editing FIX 1)

The spec for this plan described `decryptedFiles` as "a Map (line 280…)". In the **live tree at
45e2832e** `decryptedFiles` is **already an `ImmutableMap`** end-to-end:

- `ConversationListUiState.kt:69` — `val decryptedFiles: ImmutableMap<DecryptedFileKey, String> = persistentMapOf()`
- `MessageItem.kt:31` — `decryptedFiles: ImmutableMap<DecryptedFileKey, String>`
- `MediaItem.kt:105` — `decryptedFiles: ImmutableMap<DecryptedFileKey, String> = persistentMapOf()`

So the *type* of `decryptedFiles` is not the problem. The problem the spec identified is still
real: `MediaDownloadHandler.kt:284` rebuilds the whole map (`decryptedFiles.toPersistentMap()`)
on **every single file decrypt**, so its **identity** changes and every visible bubble that
receives the whole map recomposes. The genuinely *unstable* prop is **`downloadingFiles`**, which
is a bare `Set<String>` (`ConversationListUiState.kt:89`, `MessageItem.kt:39`, `MediaGallery.kt`
overloads) — Compose treats `Set` as unstable, so passing it whole forces recomposition of every
bubble on any download start/finish. **FIX 1 below makes `downloadingFiles` an `ImmutableSet`
(the safe, in-scope minimum that removes the instability) and documents the per-row-slice as a
clearly-bounded deferred follow-up**, because true per-row slicing requires re-plumbing the entire
`SentMessageBubble`/`ReceivedMessageBubble`/`MediaGallery`/`MediaItem` tree, which the spec's own
Scope forbids ("do not restructure the … MessageBubble tree beyond prop slicing").

## Why this matters

In an open conversation with media, starting or finishing a single file download changes the
identity of the whole `downloadingFiles` set (and `decryptedFiles` map), so **every visible
message bubble recomposes** even though each row only reads its own per-payload key. On a
media-heavy chat that is dozens of bubbles re-laying-out on every download tick — visible jank
while scrolling and during active media transfers. Three smaller wins compound it: an
un-remembered O(n) `find` over `activeConversations` on every recomposition of the open chat, a
`derivedStateOf` reallocated every recomposition of the list pane, and a date-format object
rebuilt per section row and per floating-date frame. Fixing these removes avoidable work from the
chat hot path without changing any displayed text or behaviour.

## Current state

### FIX 1 — unstable `downloadingFiles` set threaded whole into every bubble

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/ConversationListUiState.kt:89`
```kotlin
    val downloadingFiles: Set<String> = emptySet(),
```
Sibling fields are immutable, e.g. `:69` `decryptedFiles: ImmutableMap<…>`, `:70`
`userDefaultReactions: ImmutableList<String>`, `:71` `uploadProgress: ImmutableMap<…>`. The bare
`Set` is the odd one out.

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationContent.kt:1146` — passed
whole into every row:
```kotlin
                                            MessageItem(
                                                message = item.message,
                                                …
                                                downloadingFiles = uiState.downloadingFiles,
                                                uploadStatus = uiState.uploadProgress[item.message.id],   // <- :1147 the per-row slice pattern to mirror
```
Note `uploadStatus` at `:1147` is **already sliced per row** (`uiState.uploadProgress[item.message.id]`) — that is the exact pattern a deeper per-row slice of downloading state would mirror, but it is **out of scope** here (see Scope).

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageItem.kt:39` — receives it whole and re-passes it down (`:165` into `SentMessageBubble`, `:220` into `ReceivedMessageBubble`):
```kotlin
    downloadingFiles: Set<String>,
```

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MediaGallery.kt` — the **leaf** that
actually reads it, via a per-message-key `contains` check (key = `"${messageId}_${payload.key}"`).
Param appears on several overloads (`:72`, `:172`, `:214`, `:283`) and the read at e.g. `:101`,
`:193`, `:242`, `:263`, `:312`, `:338`, `:358`:
```kotlin
    downloadingFiles: Set<String>,
    …
                        isDownloading = downloadingFiles.contains("${messageId}_${payloads[0].key}"),
```

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/MediaDownloadHandler.kt` —
the two mutation sites whose new-identity-per-mutation is what triggers the recompositions:
```kotlin
284                    messagesUiState.update { it.copy(decryptedFiles = decryptedFiles.toPersistentMap()) }   // new map identity per decrypt
…
294                uiState.update {
295                    it.copy(downloadingFiles = it.downloadingFiles - fileKey)                               // Set minus -> new Set
296                }
```
(There is a matching `+ fileKey` add site earlier in the same function where a download starts —
find it by `grep -n "downloadingFiles" MediaDownloadHandler.kt`.)

**Convention that applies:** all collection-valued UiState fields are
`kotlinx.collections.immutable` types so Compose can treat them as stable
(`ConversationListUiState.kt:69-71` are the exemplars). `ImmutableSet` / `persistentSetOf` are
**not yet used anywhere in homebase-chat commonMain** but the `kotlinx.collections.immutable`
dependency is already on the classpath (the file already imports `ImmutableList`, `ImmutableMap`,
`persistentListOf`, `persistentMapOf`).

### FIX 2 — un-remembered O(n) `find` over `activeConversations`

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/ConversationListScreen.kt:812-814`
(inside the `detailPane { AnimatedPane { … } }` body — runs on every recomposition of the open chat):
```kotlin
                    val contentKey = scaffoldNavigator.currentDestination?.contentKey
                    val conversation =
                        uiState.activeConversations.find { it.conversation.id == contentKey }
```
Same un-remembered scan in two dialog sites: `:338` and `:425`
```kotlin
                .find { it.conversation.id == dialog.conversationId }?.conversation   // :338
                .find { it.conversation.id == dialog.conversationId }                 // :425
```
and twice in the VM (`ConversationListViewModel.kt:1273-1274`, `:1409-1410`) — those are inside
event handlers, not composition, so they recompute far less often.

**Exemplar to model after:** the `remember(key) { … }` derivation a few lines up,
`ConversationListScreen.kt:711-713`:
```kotlin
    val loadedConversationIds = remember(uiState.activeConversations) {
        uiState.activeConversations.mapTo(hashSetOf()) { it.conversation.id }
    }
```

### FIX 3 — `derivedStateOf` not wrapped in `remember`

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationListPane.kt:127` (inside
`BoxWithConstraints`):
```kotlin
        val iconOnlyMode by derivedStateOf { maxWidth <= 96.dp }
```
A bare `derivedStateOf` allocates a fresh `DerivedState` on **every** recomposition, defeating its
caching. The fix is the standard `remember { derivedStateOf { … } }`.

### FIX 4 — date-format object rebuilt per section row / per floating-date frame

`homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationContent.kt:2014-2032`:
```kotlin
@Composable
private fun getDateSectionLabel(messageDate: LocalDate): String {
    val timezone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(timezone).date
    val yesterday = today.minus(1, DateTimeUnit.DAY)

    return when (messageDate) {
        today -> stringResource(MR.string.time_today)
        yesterday -> stringResource(MR.string.time_yesterday)
        else -> {
            val format = LocalDate.Format {            // <- rebuilt every call
                monthName(MonthNames.ENGLISH_ABBREVIATED)
                char(' ')
                day()
            }
            messageDate.format(format)
        }
    }
}
```
Called from two hot spots: `:1085` `MessagesSection(text = getDateSectionLabel(item.date))` (once
per `Section` row) and `:1199` `floatingDateLabel?.let { lastDateText = getDateSectionLabel(it) }`
(once per floating-date frame while scrolling). The relevant imports already exist at the top of
the file (`:214` `DateTimeUnit`, `:215` `LocalDate`, `:216` `TimeZone`, `:217` `format`, `:218`
`MonthNames`, `:219` `char`, `:220` `minus`, `:221` `toLocalDateTime`, `:224` `Clock`).

**The smallest correct fix** (chosen — see Steps) is to hoist the static `LocalDate.Format` to a
module-level `val` (it never changes; right now it is allocated on every non-today/yesterday
call). This is a pure micro-optimisation with zero behaviour change and no signature churn. The
deeper "precompute the label string in the VM Section model" option is documented as a deferred
follow-up in Maintenance notes — the `Section` model is `data class Section(val date: LocalDate)`
(`ConversationListUiState.kt:230`) built on `Dispatchers.Default` in the VM at
`ConversationListViewModel.kt:1447`, so it *could* carry a precomputed string, but that touches
the model shape and the VM and is not the smallest fix.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run first) | `git diff --stat 45e2832e..HEAD -- homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/ConversationListUiState.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationContent.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationListPane.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/ConversationListScreen.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/MediaDownloadHandler.kt` | empty output (no in-scope file changed since planning) |
| Compile gate (JVM/Desktop) — run after EACH fix | `./gradlew :homebase-chat:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile gate (Android) — run after EACH fix | `./gradlew :homebase-chat:compileAndroidMain` | `BUILD SUCCESSFUL` |
| Compile gate (iOS, macOS host only) — at least once at the end | `./gradlew :homebase-chat:compileKotlinIosSimulatorArm64` | `BUILD SUCCESSFUL` |
| Test gate — run after EACH fix | `./gradlew :homebase-chat:jvmTest` | `BUILD SUCCESSFUL`, 0 failures |
| Konsist arch test (string-literal guard) | `./gradlew :homebase-common:jvmTest` | `BUILD SUCCESSFUL` (you add no `Text("…")` literals) |
| Grep: confirm no bare `Set<String>` left on the downloading prop | `rg -n "downloadingFiles: Set<String>" homebase-chat/src` | no matches |
| Grep: confirm un-remembered detail-pane find is gone | `rg -n "uiState.activeConversations.find" homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/ConversationListScreen.kt` | no match at the detail-pane site (lines ~812-814) |

> Note on KMP gradle tasks: homebase-chat is a **library** module — it has NO `assembleDebug` /
> `compileDebugKotlinAndroid` tasks (those exist only on `androidApp`). Use the target-named
> compile tasks above. iOS compile only works on a macOS host with the toolchain installed; if it
> is unavailable, note that in your report rather than treating it as a failure.

## Scope

**In scope (only these files may be modified):**
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/ConversationListUiState.kt` — change `downloadingFiles` type (FIX 1).
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/MediaDownloadHandler.kt` — keep `downloadingFiles` mutations producing an `ImmutableSet` (FIX 1).
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MessageItem.kt` — `downloadingFiles` param type (FIX 1).
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/MediaGallery.kt` — `downloadingFiles` param type on each overload (FIX 1).
- Any other bubble file that declares `downloadingFiles: Set<String>` in its signature (SentMessageBubble / ReceivedMessageBubble — discover with the grep in Step 1; type change only, no restructuring) (FIX 1).
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/ConversationListScreen.kt` — wrap the detail-pane `find` in `remember` (FIX 2).
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationListPane.kt` — wrap `derivedStateOf` in `remember` (FIX 3).
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationContent.kt` — hoist the `LocalDate.Format` (FIX 4).
- `plans/011-chat-recomposition-fixes.md` (this file) and `plans/README.md` (row update).

**Out of scope (do NOT touch):**
- The `SentMessageBubble` / `ReceivedMessageBubble` / `MediaGallery` / `MediaItem` **internal layout / tree structure** — only the `downloadingFiles` *parameter type* may change; do NOT add a new per-row Boolean parameter or restructure the gallery (spec: "do not restructure the LazyColumn or MessageBubble tree beyond prop slicing"). The deeper per-row slice is a deferred follow-up.
- `decryptedFiles` type — it is **already** `ImmutableMap`; do not change it. (Reducing its per-decrypt new-map churn is a deferred follow-up, not part of this plan.)
- `ConversationListViewModel.kt` `find` sites (`:1273`, `:1409`) — they run in event handlers, not composition; changing them is not needed and the VM is otherwise out of scope for these UI perf fixes. (FIX 2 applies "at least to the detail-pane site" per spec.)
- The two dialog-site `find`s (`ConversationListScreen.kt:338`, `:425`) — optional; only do them if FIX 2 leaves the build green and you want the extra wins. Treat as nice-to-have, gate them the same way.
- Any displayed text / string resources — FIX 4 must not change what the user sees.

## Steps

Order: FIX 3 (tiniest) → FIX 4 → FIX 2 → FIX 1 (largest blast radius last). Each step ends green.

### Step 0 — Drift check
Run the drift-check command (Commands table, first row). Expected: empty output.
If any in-scope file shows changes, open it and compare against the Current-state excerpts above;
on any mismatch, **STOP** and report.
Verify: `git status --short` → only this plan file (untracked) before you start editing.

### Step 1 — FIX 3: remember the derivedStateOf (ConversationListPane.kt:127)
Change:
```kotlin
        val iconOnlyMode by derivedStateOf { maxWidth <= 96.dp }
```
to:
```kotlin
        val iconOnlyMode by remember { derivedStateOf { maxWidth <= 96.dp } }
```
`remember` is already imported (the file uses `remember { … }` at `:110`, `:111`).
Verify: `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`; then
`./gradlew :homebase-chat:jvmTest` → `BUILD SUCCESSFUL`. Commit:
`fix(chat): remember derivedStateOf for icon-only mode in ConversationListPane`.

### Step 2 — FIX 4: hoist the LocalDate.Format to a module-level val (ConversationContent.kt)
Add a top-level (file-scope) `private val` near the existing `private val FloatingDateShape =
RoundedCornerShape(12.dp)` at `:2012`:
```kotlin
private val SectionDateFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    day()
}
```
Then in `getDateSectionLabel` (`:2023-2030`) replace the inline `val format = LocalDate.Format { … }`
+ `messageDate.format(format)` with `messageDate.format(SectionDateFormat)`. The `today`/`yesterday`
reads stay as-is (they are cheap and `@Composable` already; leaving them avoids any behaviour
change). Do NOT change the returned strings or the `time_today` / `time_yesterday` branches.
Verify: `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`; `./gradlew
:homebase-chat:jvmTest` → `BUILD SUCCESSFUL`. Commit:
`perf(chat): hoist section date format to module-level val`.

### Step 3 — FIX 2: remember the detail-pane conversation lookup (ConversationListScreen.kt:812-814)
Change:
```kotlin
                    val contentKey = scaffoldNavigator.currentDestination?.contentKey
                    val conversation =
                        uiState.activeConversations.find { it.conversation.id == contentKey }
```
to:
```kotlin
                    val contentKey = scaffoldNavigator.currentDestination?.contentKey
                    val conversation = remember(uiState.activeConversations, contentKey) {
                        uiState.activeConversations.find { it.conversation.id == contentKey }
                    }
```
`remember` is already imported and used throughout this file (e.g. `:711`). This recomputes only
when the conversation list or the selected key actually changes — not on every recomposition.
(Optional, same gate: you may also wrap the `:338` and `:425` dialog-site finds in
`remember(uiState.activeConversations, dialog.conversationId) { … }`. Skip if anything goes amber.)
Verify: `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`; `./gradlew
:homebase-chat:jvmTest` → `BUILD SUCCESSFUL`. Commit:
`perf(chat): memoize detail-pane conversation lookup`.

### Step 4 — FIX 1: make downloadingFiles an ImmutableSet (type-only change across the tree)
This is the widest-blast-radius change; it is purely a **type swap** `Set<String>` →
`ImmutableSet<String>`, no restructuring. Do it in one commit so the build is never broken
mid-tree.

4a. Discover every declaration first:
```
rg -n "downloadingFiles: Set<String>" homebase-chat/src
rg -n "downloadingFiles =" homebase-chat/src
```
Expected declarations to change: `ConversationListUiState.kt:89`, `MessageItem.kt:39`,
`MediaGallery.kt` (overloads at `:72`, `:172`, `:214`, `:283`), and `SentMessageBubble` /
`ReceivedMessageBubble` (whatever files those are in — the grep will show them). Change each
`downloadingFiles: Set<String>` to `downloadingFiles: ImmutableSet<String>`. Add
`import kotlinx.collections.immutable.ImmutableSet` to each file that newly references the type
(see "MR.string needs explicit import" discipline — a missing immutable-collections import can
compile against a stale cache but fail clean CI).

4b. In `ConversationListUiState.kt:89` change the default too:
```kotlin
    val downloadingFiles: ImmutableSet<String> = persistentSetOf(),
```
Add `import kotlinx.collections.immutable.ImmutableSet` and
`import kotlinx.collections.immutable.persistentSetOf` (the file already imports the
`persistentListOf`/`persistentMapOf` siblings).

4c. In `MediaDownloadHandler.kt`, keep the field an `ImmutableSet` at both mutation sites:
- the **remove** site (`:294-296`): `it.downloadingFiles - fileKey` — `PersistentSet.minus` already
  returns a `PersistentSet` (which is an `ImmutableSet`), so this likely compiles unchanged. If the
  inferred type is too wide, wrap with `.toPersistentSet()` and add
  `import kotlinx.collections.immutable.toPersistentSet`.
- the **add** site (the `… + fileKey` earlier in the same function — find via the Step-4a grep):
  same reasoning; `PersistentSet.plus` returns a `PersistentSet`. Only add `.toPersistentSet()`
  if the compiler complains.
- the **initial value** the function reads must start as a `persistentSetOf()` via the UiState
  default from 4b, so `+`/`-` stay on the persistent type.

4d. Leaf reads in `MediaGallery.kt` (`downloadingFiles.contains("…")`) need **no change** —
`ImmutableSet` has `.contains`.

4e. The whole-prop pass-throughs (`ConversationContent.kt:1146`, `MessageItem.kt:165`/`:220`,
gallery overload call sites) need **no change** — they just forward the value; only the declared
*types* changed.

Verify (all must pass before committing this step):
- `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`
- `./gradlew :homebase-chat:compileAndroidMain` → `BUILD SUCCESSFUL`
- `./gradlew :homebase-chat:jvmTest` → `BUILD SUCCESSFUL`
- `rg -n "downloadingFiles: Set<String>" homebase-chat/src` → no matches
Commit: `perf(chat): make downloadingFiles an ImmutableSet to stop bubble-wide recomposition`.

### Step 5 — Final cross-target gate + README row
- `./gradlew :homebase-chat:compileAndroidMain` → `BUILD SUCCESSFUL`
- `./gradlew :homebase-chat:compileKotlinIosSimulatorArm64` (macOS host only) → `BUILD SUCCESSFUL`
  (if no macOS toolchain, note it in the report — not a failure of this plan).
- `./gradlew :homebase-common:jvmTest` → `BUILD SUCCESSFUL` (Konsist string-literal guard; you
  added no `Text` literals so this is a sanity check).
- Update the row for plan 011 in `plans/README.md` (create the file with a table header if it does
  not yet exist, matching the row format other plans expect): status → done, link to this file.
Verify: `git status --short` shows only the in-scope files from Scope plus this plan + README.

## Test plan

These are pure perf refactors with **no behaviour change**, so the primary guard is the existing
test + compile matrix, not new assertions. Add **one small JVM unit test** to lock the FIX 4
formatting (the only fix with a pure-function output worth pinning):

- New file: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/widget/SectionDateFormatTest.kt`
  - Case 1: `SectionDateFormat.format(LocalDate(2026, 1, 5))` → `"Jan 5"` (verifies the hoisted
    module-level format produces the identical `MonthNames.ENGLISH_ABBREVIATED` + space + day
    output as the old inline format — this is the regression guard for FIX 4).
  - Case 2: a two-digit-day date, e.g. `LocalDate(2026, 12, 25)` → `"Dec 25"`.
  - To make this testable, the `SectionDateFormat` val must be reachable from test code. If it is
    `private` and file-local, either (a) mark it `internal` (preferred — still hidden from the
    public API, visible to `jvmTest`), or (b) inline the same `LocalDate.Format { … }` block in the
    test and assert both produce equal output. Pick (a).
- Model after: any existing JVM test under `homebase-chat/src/jvmTest/…` that uses **fakes, not
  mocks** (this repo uses fakes — no Mockito/MockK). This test needs no fakes at all; it is a pure
  formatting assertion using `kotlinx.datetime.LocalDate`.
- Do **not** write a Compose recomposition-count test here (no Compose test harness is wired for
  this module on JVM); recomposition validation is a manual step in Maintenance notes.
- Verify command: `./gradlew :homebase-chat:jvmTest` → `BUILD SUCCESSFUL`, the 2 new cases pass.

If marking `SectionDateFormat` `internal` causes a Konsist/lint complaint, fall back to test
option (b) and note it; do not expand scope to silence a linter.

## Done criteria

- [ ] `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`
- [ ] `./gradlew :homebase-chat:compileAndroidMain` → `BUILD SUCCESSFUL`
- [ ] `./gradlew :homebase-chat:compileKotlinIosSimulatorArm64` → `BUILD SUCCESSFUL` (macOS host; else noted)
- [ ] `./gradlew :homebase-chat:jvmTest` → `BUILD SUCCESSFUL`, 0 failures, 2 new `SectionDateFormatTest` cases pass
- [ ] `./gradlew :homebase-common:jvmTest` → `BUILD SUCCESSFUL` (no new `Text` literals)
- [ ] `rg -n "downloadingFiles: Set<String>" homebase-chat/src` → no matches
- [ ] `rg -n "uiState.activeConversations.find" homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/ConversationListScreen.kt` → detail-pane site (≈812-814) is now inside a `remember(…) { … }`
- [ ] `rg -n "by derivedStateOf \{" homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationListPane.kt` → no bare `by derivedStateOf {` (now `remember { derivedStateOf { … } }`)
- [ ] `rg -n "LocalDate.Format \{" homebase-chat/src/commonMain/kotlin/id/homebase/chat/widget/ConversationContent.kt` → exactly one occurrence, at module scope (`SectionDateFormat`), none inside `getDateSectionLabel`
- [ ] `git status --short` shows only in-scope files (Scope list) + this plan + `plans/README.md`
- [ ] `plans/README.md` row for plan 011 updated to done

## STOP conditions

- **Drift:** Step 0 shows an in-scope file changed since 45e2832e and the Current-state excerpt no longer matches live code.
- **Gate fails twice:** any per-fix `compileKotlinJvm` or `jvmTest` fails twice in a row for the same fix → STOP, report which fix and the error. The other three fixes remain independently landable.
- **Out-of-scope file needed:** FIX 1 cannot be made to compile by *type-only* changes (e.g. a `Set`-specific API is in use that `ImmutableSet` lacks, forcing a structural change to a bubble) → STOP; do NOT restructure the bubble tree. Report the call site. (Fallback: leave FIX 1 unshipped and land FIX 2/3/4.)
- **Assumption false:** `kotlinx.collections.immutable.persistentSetOf` / `ImmutableSet` is not resolvable in `homebase-chat` commonMain (the dep is present for List/Map, so this should not happen) → STOP and report; the `ImmutableList`/`ImmutableMap` imports already in `ConversationListUiState.kt` prove the artifact is on the classpath, so investigate the exact symbol name before giving up.
- **Konsist failure:** `:homebase-common:jvmTest` fails because of a `Text("…")` literal you introduced → you added a string literal somewhere; revert it, use `stringResource`. (None of these fixes should add UI text.)

## Maintenance notes

- **Deferred follow-up A (the real FIX 1):** per-row slicing of download/decrypt state. Today the
  whole `downloadingFiles` set and whole `decryptedFiles` map are threaded down to the
  `MediaGallery` leaf, which does a per-payload `contains`/`get`. The ideal is to compute, in the
  `items {}` block at `ConversationContent.kt:1112-1155`, a per-row `isDownloading: Boolean` and a
  resolved per-row decrypted-path `String?`, and pass only those — mirroring the
  `uploadStatus = uiState.uploadProgress[item.message.id]` slice already at `:1147`. This requires
  re-plumbing the `SentMessageBubble` / `ReceivedMessageBubble` / `MediaGallery` / `MediaItem`
  signatures (a message can have multiple payloads, so the "slice" is itself a small per-payload
  map keyed by `payload.key`, not a single Boolean) and is explicitly **out of scope** for plan 011.
  The `ImmutableSet` change here is the safe interim: it removes the *type-instability* recomposition
  trigger, though a *content* change to the set still propagates a new identity. Track this as its
  own plan.
- **Deferred follow-up B:** `decryptedFiles.toPersistentMap()` rebuilds the whole map per decrypt
  (`MediaDownloadHandler.kt:284`). Switching to `it.decryptedFiles.put(key, path)` on the existing
  `PersistentMap` (structural sharing) would avoid the full-map copy and keep churn proportional to
  the change. Out of scope here; pairs naturally with follow-up A.
- **Deferred follow-up C (the deeper FIX 4):** precompute the section label `String` in the VM when
  building `MessageListContentModel.Section` (`ConversationListViewModel.kt:1447`, on
  `Dispatchers.Default`), so the composable reads a ready string instead of calling
  `Clock.System.now()` per row. This changes the `Section(val date: LocalDate)` model shape
  (`ConversationListUiState.kt:230`) — note the model `id` is derived from `date.toString()`, so if
  you add a `label` field keep `date` as the identity source or the LazyColumn keys shift. Not the
  smallest fix; deferred.
- **What a reviewer should scrutinize:** (1) FIX 1 must be type-only — diff should show no new
  parameters and no gallery restructuring; (2) FIX 4 must not change displayed text — the
  `SectionDateFormatTest` Jan-5/Dec-25 cases are the guard; (3) FIX 2's `remember` keys
  (`uiState.activeConversations`, `contentKey`) must both be present or the memo goes stale on
  conversation switch.
- **Manual validation (recommended):** open a media-heavy conversation, enable Compose layout
  inspector / recomposition counts (Android Studio), start a file download, and confirm only the
  downloading bubble (and not every visible bubble) recomposes on download start/finish. Capture
  before/after counts in the PR description.
