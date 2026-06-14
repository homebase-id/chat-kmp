# Plan 005: Make MessageMapper's parse-failure fallback null-safe so legacy null-id messages can't NPE-and-vanish

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/MessageMapper.kt homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/ChatMessageStreamMapperTest.kt`. If either in-scope file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P1
- Effort: S
- Risk: LOW
- Depends on: none (pairs with plan 023, which covers the full mapper test suite; this plan is self-contained and can land first or alone)
- Category: bug
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
`mapToMessageData` has a `catch (t: Throwable)` fallback whose entire job is to rescue a message that failed the happy-path parse and still show it as a "Failed to parse message from server" bubble instead of dropping it silently. But the fallback re-uses `appData.uniqueId!!` and `appData.groupId!!` (non-null assertions). The most common reason a message reaches that catch is precisely a **null** `uniqueId` or `groupId` (legacy/malformed records — the `require(appData.uniqueId != null)` / `require(appData.groupId != null)` checks throw exactly those). So the fallback that exists to rescue null-id messages instead **NPEs on them**, falls into the inner `catch`, and returns `null` — the message vanishes from the conversation with no visible "Failed to parse" bubble and no clear single log line explaining why. Worse, the inner catch's `return null` is stranded *inside* the `Logger.e(t2) { }` message lambda (so it's dead code that's never the actual return path), making the control flow misleading to any future reader. This plan makes the fallback degrade gracefully: a null `uniqueId` falls back to the always-present `header.fileId`; a genuinely null `groupId` (which cannot map to any conversation) returns `null` *explicitly* with one clear log instead of throwing; and the stranded `return null` is moved to real code.

## Current state

### File: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/MessageMapper.kt` — the decoder under fix

Signature (no `conversationId` is passed by any caller — verified across `ChatMessageStream.kt`, `ConversationMapper.kt`, `ConversationStream.kt`, `DesktopChatNotificationBridge.kt`, and the tests):
```kotlin
85  suspend fun mapToMessageData(
86      header: HomebaseFile,
87      credentialsManager: CredentialsManager,
88      displayNameResolver: suspend (HomebaseFile) -> String = {
89          it.fileMetadata.originalAuthor?.domainName ?: ""
90      }
91  ): MessageUiModel? {
```

The deleted-message branch already uses the safe id fallback (this is the exemplar to copy for `id`) — but note its `groupId` is still a bare `!!`:
```kotlin
131          return MessageUiModel(
132              id = appData.uniqueId ?: header.fileId,
...
135              conversationId = appData.groupId!!,
```

The happy-path requires (OUT OF SCOPE — do not touch these; they are correct gates):
```kotlin
159          require(content != null)
160          require(appData.uniqueId != null)
161          require(appData.groupId != null)
```

The catch + fallback (THE BUG — lines 292–335):
```kotlin
292      } catch (t: Throwable) {
293
294          Logger.e(t) {
295              "failed while mapping a message with uniqueId ${appData.uniqueId} and fileId ${header.fileId} " +
                    ... (long diagnostic message lambda) ...
300          }
301
302          try {
303              return MessageUiModel(
304                  id = appData.uniqueId!!,            // <-- NPEs when the require that failed was uniqueId==null
305                  globalTransitId = metadata.globalTransitId,
306                  fileId = header.fileId,
307                  conversationId = appData.groupId!!, // <-- NPEs when the require that failed was groupId==null
308                  content = "Failed to parse message from server",
309                  userDate = metadata.created.toInstant(),
310                  modified = metadata.updated.toInstant(),
311                  created = metadata.created.toInstant(),
312                  originalAuthor = metadata.originalAuthor,
313                  sender = metadata.senderOdinId,
314                  displayName = metadata.originalAuthor?.domainName ?: "",
315                  messageAppData = MessageAppData(),
316                  localReadTimestamp = localReadTimestamp,
317                  ownReactions = ownReactions,
318                  reactionPreview = metadata.reactionPreview,
319                  previewThumbnail = metadata.appData.previewThumbnail,
320                  payloads = metadata.payloads?.toPersistentList(),
321                  keyHeader = header.keyHeader,
322                  versionTag = Uuid.NIL,
323                  isPendingSend = false,
324                  isStatusMessage = isStatusMessage,
325                  hasMore = hasMore
326              )
327          } catch (t2: Throwable) {
328              Logger.e(t2) {
329                  "Failed in fallback handling for parsing a message: fileId ${header.fileId}"
330                  return null                          // <-- STRANDED inside the message lambda; dead code
331              }
332          }
333
334          return null                                  // <-- the function's actual return on inner-catch
335      }
```

### Supporting type facts (verified — do NOT change these files)
- `header.fileId` is a **non-null** `Uuid` — `homebase-api/.../drives/HomebaseFile.kt:17` (`val fileId: Uuid,`). Always safe to use as an id fallback.
- `appData.uniqueId` and `appData.groupId` are both **nullable** — `homebase-api/.../drives/files/FileMetadata.kt:40` (`val uniqueId: Uuid? = null,`) and `:44` (`val groupId: Uuid? = null,`).
- `MessageUiModel.id: Uuid` and `MessageUiModel.conversationId: Uuid` are both **non-null** — `homebase-chat/.../data/MessageUiModel.kt:21` and `:26`. Therefore there is no way to construct a `MessageUiModel` with a null `conversationId`; a message whose `groupId` is null genuinely cannot be placed in any conversation, so the only correct outcome for that case is `return null` (explicit, logged).

### Convention that applies
- No new user-facing strings are added (the fallback uses the existing literal `"Failed to parse message from server"`, which is non-UI content text passed as a model field, not a `Text(...)` composable), so the Konsist `ArchitectureTest` is not engaged here.
- Tests use FAKES not mocks; shared mapper tests live in `homebase-chat/src/jvmTest`. Exemplar to match: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/ChatMessageStreamMapperTest.kt` (its `buildChatMessageHeader` JSON helper + `runTest` + `assertNotNull` style).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run first) | `git diff --stat 45e2832e..HEAD -- homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/MessageMapper.kt homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/ChatMessageStreamMapperTest.kt` | No output (no drift) — else compare excerpts before proceeding |
| Compile the module (JVM) | `./gradlew :homebase-chat:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile test sources (JVM) | `./gradlew :homebase-chat:compileTestKotlinJvm` | `BUILD SUCCESSFUL` |
| Run the module's JVM tests | `./gradlew :homebase-chat:jvmTest --rerun-tasks` | `BUILD SUCCESSFUL`, all tests green incl. the 2 new ones |
| Confirm no `!!` survives in the catch block | `sed -n '292,336p' homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/MessageMapper.kt \| grep -n '!!'` | No output |
| Confirm no in-scope drift in git status | `git status --porcelain` | Only the two in-scope files (+ this plan / README) listed |

## Scope
**In scope (modify only these):**
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/MessageMapper.kt` — fix the catch-fallback (lines ~302–334 only).
- `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/ChatMessageStreamMapperTest.kt` — add the regression tests.

**Out of scope (do NOT touch):**
- The happy-path `require(...)` lines 159–161 in `MessageMapper.kt` — they are correct gates; the fix is in the catch, not in removing the gates.
- The deleted-message branch (lines 125–157) — it already uses the safe id fallback; its `groupId!!` at line 135 is a separate latent issue tracked elsewhere, do NOT "fix" it here (changing deleted-branch behaviour is out of scope and risks the deleted-message tests).
- `MessageContentParser` and any content-parsing code — unrelated to the fallback.
- `MessageUiModel.kt`, `FileMetadata.kt`, `HomebaseFile.kt` — type definitions are correct as-is.
- Any caller of `mapToMessageData` — the signature does NOT change (no new parameter), so no caller edits are needed.

## Steps

1. **Open `MessageMapper.kt` and replace the fallback's `id` assignment.** In the `try`-block at line ~303, change line 304 from `id = appData.uniqueId!!,` to `id = appData.uniqueId ?: header.fileId,`. This mirrors the deleted-branch exemplar at line 132. `header.fileId` is a non-null `Uuid`, so this never NPEs.
   - Verify: `./gradlew :homebase-chat:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

2. **Handle the null `groupId` explicitly *before* constructing the fallback model.** Because `MessageUiModel.conversationId` is non-null and no conversationId is available from the caller, a null `groupId` cannot produce a valid bubble. Immediately inside the `try` (line 302), before the `return MessageUiModel(`, add an explicit guard:
   ```kotlin
   try {
       val fallbackConversationId = appData.groupId
       if (fallbackConversationId == null) {
           // A message with no groupId cannot be attached to any conversation,
           // and the envelope carries no conversation id we can fall back to
           // (header.fileId identifies the file, not the conversation). Drop it
           // explicitly with one clear line rather than NPEing in the model ctor.
           Logger.e(t) {
               "Dropping un-mappable message: null groupId. " +
                   "fileId=${header.fileId} uniqueId=${appData.uniqueId} " +
                   "originalAuthor=${metadata.originalAuthor?.domainName}"
           }
           return null
       }
       return MessageUiModel(
           id = appData.uniqueId ?: header.fileId,
           ...
           conversationId = fallbackConversationId,   // was appData.groupId!!
           ...
       )
   ```
   Change line 307 from `conversationId = appData.groupId!!,` to `conversationId = fallbackConversationId,`. Keep the original outer `Logger.e(t) { ... }` at line 294–300 as-is (it still logs the original failure); the new guard adds a second, specific line only on the null-groupId path. Pass the *outer* throwable `t` into the new `Logger.e(t) { ... }` — `t` is in scope here (the enclosing `catch (t: Throwable)`).
   - Verify: `./gradlew :homebase-chat:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

3. **Move the stranded `return null` out of the inner-catch message lambda.** Replace the inner catch (lines 327–331) so the log fires and then the function returns. The current code's `return null` lives inside the `Logger.e(t2) { ... }` lambda (a non-local return that makes the message lambda's body never produce a string, and is misleading). Rewrite to:
   ```kotlin
   } catch (t2: Throwable) {
       Logger.e(t2) {
           "Failed in fallback handling for parsing a message: fileId ${header.fileId}"
       }
       return null
   }
   ```
   The trailing `return null` at line 334 (outside both catches) can stay — it is now reachable only if the inner `try` neither returns a model nor throws, which is structurally impossible, but leaving it satisfies the compiler's definite-return analysis and is harmless. Do NOT delete it unless the compiler complains it's unreachable; if it does complain, remove it.
   - Verify: `./gradlew :homebase-chat:compileKotlinJvm` -> `BUILD SUCCESSFUL`, and `sed -n '292,336p' homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/MessageMapper.kt | grep -n '!!'` -> no output.

4. **Add the regression tests** (see Test plan) to `ChatMessageStreamMapperTest.kt`. First extend the `buildChatMessageHeader` helper to allow null `uniqueId` / `groupId`, then add the two test cases.
   - Verify: `./gradlew :homebase-chat:compileTestKotlinJvm` -> `BUILD SUCCESSFUL`.

5. **Run the module tests.**
   - Verify: `./gradlew :homebase-chat:jvmTest --rerun-tasks` -> `BUILD SUCCESSFUL`, all green (existing + 2 new).

6. **Update `plans/README.md`** — mark this plan's row Done (if `plans/README.md` does not yet exist, create it with a header row `| Plan | Title | Status |` and add this row `| 005 | MessageMapper fallback null-safety | Done |`).
   - Verify: `git status --porcelain` shows only the in-scope files plus this plan and README.

## Test plan

Add to `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/ChatMessageStreamMapperTest.kt` (model after its existing `buildChatMessageHeader` + `runTest` cases).

First, make the JSON helper accept nullable ids. The current helper hardcodes:
```kotlin
"uniqueId": "${Uuid.random()}",
...
"groupId": "${Uuid.random()}",
```
Parametrize it (add two params with defaults so all existing call sites keep compiling unchanged):
```kotlin
private fun buildChatMessageHeader(
    localAppDataJson: String?,
    fileState: String = "active",
    uniqueIdJson: String = "\"${Uuid.random()}\"",   // pass "null" for a null uniqueId
    groupIdJson: String = "\"${Uuid.random()}\"",     // pass "null" for a null groupId
): HomebaseFile {
    ...
    "uniqueId": $uniqueIdJson,
    ...
    "groupId": $groupIdJson,
    ...
}
```
(Emit the values *without* surrounding quotes in the template — the param already supplies its own quotes or the literal `null`.)

New cases:

- **`mapToMessageData_nullGroupId_returnsNullWithoutThrowing`** — build a header with `groupIdJson = "null"` (valid uniqueId/content). Assert `mapToMessageData(header, cm)` returns `null` and does **not** throw. This is the exact regression: pre-fix the fallback did `appData.groupId!!` and NPEd inside the inner catch, returning null *via a thrown exception* rather than the explicit drop. `assertNull(result)`.

- **`mapToMessageData_nullUniqueId_doesNotThrow_andUsesFileIdFallback`** — build a header with `uniqueIdJson = "null"` (valid groupId) **and** force the happy path into the catch. With a null `uniqueId` but valid `content` and `groupId`, the happy path throws at `require(appData.uniqueId != null)` (line 160) → catch → fallback. Assert the result is **non-null** (the "Failed to parse" rescue bubble is produced, message not dropped) and that `result.id == header.fileId` (the fileId fallback was used). Capture `header.fileId` before the call: read it via the parsed model (`header.fileId`). `assertNotNull(result); assertEquals(header.fileId, result.id)`. Optionally assert `result.content == "Failed to parse message from server"`.

Imports to add to the test file (if not already present): `kotlin.test.assertNull` (the file already imports `assertNotNull`, `assertEquals`, `assertTrue`).

Verify command: `./gradlew :homebase-chat:jvmTest --rerun-tasks` -> `BUILD SUCCESSFUL`, both new tests pass.

## Done criteria
- [ ] `./gradlew :homebase-chat:compileKotlinJvm` -> `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-chat:compileTestKotlinJvm` -> `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-chat:jvmTest --rerun-tasks` -> `BUILD SUCCESSFUL`, the 2 new tests pass, no existing test regressed.
- [ ] `sed -n '292,336p' homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/MessageMapper.kt | grep -n '!!'` returns nothing (no `!!` remains in the catch/fallback block).
- [ ] The fallback `id` line reads `id = appData.uniqueId ?: header.fileId,` and the `conversationId` is assigned from a non-null local that was null-checked with an explicit `return null` ahead of it.
- [ ] The inner `catch (t2: Throwable)` logs and then `return null` *outside* the `Logger.e` lambda.
- [ ] `git status --porcelain` lists only `MessageMapper.kt`, `ChatMessageStreamMapperTest.kt`, `plans/005-messagemapper-fallback-null-safety.md`, and `plans/README.md`.
- [ ] This plan's row in `plans/README.md` is marked Done.

## STOP conditions
- **Drift:** the `git diff --stat 45e2832e..HEAD` drift check shows either in-scope file changed AND the Current-state excerpts above no longer match live code — STOP and report the mismatch.
- **Verification fails twice:** if `:homebase-chat:jvmTest` fails twice in a row on the same step after a genuine fix attempt — STOP and report the failing test + stack.
- **Out-of-scope file needed:** if making the fallback compile requires editing any caller, `MessageUiModel.kt`, `FileMetadata.kt`, or removing a `require(...)` at lines 159–161 — STOP; the design assumption (signature unchanged, types as documented) is wrong.
- **Assumption falsified:** if `MessageUiModel.conversationId` turns out to be nullable, or `header.fileId` turns out to be nullable, or a caller *does* pass a conversationId — STOP; the "return null on null groupId" decision must be revisited (you'd thread the real conversationId in instead).

## Maintenance notes
- The deleted-message branch at line 135 still has `conversationId = appData.groupId!!`. It is intentionally left untouched here (out of scope) but is the same latent class of bug; a follow-up could apply the identical guard. Flag it for plan 023 / a reviewer.
- A reviewer should scrutinize: (a) that the new null-groupId guard's `Logger.e(t)` uses the *outer* `t` (in scope) and not an undefined symbol; (b) that the explicit `return null` for null groupId is intentional and documented (a message with no conversation id is genuinely un-renderable — this is not silently dropping a recoverable message); (c) that no `!!` was reintroduced.
- If the wider goal is "no chat message ever silently vanishes," the real fix is upstream: stop persisting/accepting messages with null groupId at all. This plan only makes the *fallback* honest (drop-with-a-log vs NPE-then-drop). The 14-site `!!` audit (memory: "Non-Null Assertions: 14 Sites in Chat Production Code — MessageMapper is the Core Risk") is the broader tech-debt context; this plan closes the two highest-risk sites.
