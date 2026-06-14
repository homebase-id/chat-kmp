# Plan 023: Add a characterization test for MessageMapper.mapToMessageData (the receive-side parser)

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/MessageMapper.kt homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/ChatMessageStreamMapperTest.kt homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/MessageMapperDeliveryStatusTest.kt`. On mismatch with the Current-state excerpts below, STOP.

## Status
- Priority: P2
- Effort: M
- Risk: LOW
- Depends on: none. **Pairs with plan 005** (the null-id fallback fix). Write THIS first so plan 005's source change lands against a green safety net. The `nullUniqueId` / `nullGroupId` cases below are written as the assertions the 005 fix must satisfy — run them before 005 to watch the null-id case **fail**, after 005 to watch it **pass**.
- Category: tests
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
`mapToMessageData` is the single decoder that turns every encrypted `HomebaseFile` fresh off the drive into a `MessageUiModel?` shown in a conversation. It is ~250 lines of branching: deleted vs status-message vs typed-content (event/dice/groodle via `MessageContentParser`) vs plain-text/media; a `GroupHealLocalCleanup` peer-drop; `isFailedSend`-wins-over-`isPendingSend` precedence; `ownReactions` JSON decode; and a parse-failure `catch` fallback. Today the **only** test touching this file is `MessageMapperDeliveryStatusTest`, which covers just the pure `getDeliveryStatus` helper — the whole `mapToMessageData` branch matrix is uncovered except for the `ownReactions` / send-tag cases in `ChatMessageStreamMapperTest`. Plan 005 is about to rewrite the most fragile branch (the null-id catch fallback). Without a characterization test pinning the current behaviour of every branch first, that change is unguarded: a regression in the status-message drop, the typed-content fallback, or the deleted-branch id could ship silently. This plan adds a single self-contained `MessageMapperTest` that locks in the observable contract of each branch and bakes in the plan-005 regression as an executable assertion.

## Current state

### File under test: `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/MessageMapper.kt`
`mapToMessageData` signature (no `conversationId` param; the default `displayNameResolver` returns the author's domain):
```kotlin
85  suspend fun mapToMessageData(
86      header: HomebaseFile,
87      credentialsManager: CredentialsManager,
88      displayNameResolver: suspend (HomebaseFile) -> String = {
89          it.fileMetadata.originalAuthor?.domainName ?: ""
90      }
91  ): MessageUiModel? {
```

The branches this plan characterizes (all line numbers verified at commit 45e2832e):

- **Active-domain read** — `val domain = credentialsManager.requireActiveDomain()` (line 93). The test's `CredentialsManager` must have active credentials or this throws (model after `ChatMessageStreamMapperTest.createTestCredentialsManager`).
- **Deleted branch** (lines 123–157): `if (header.isSoftDeleted())` → returns a model with `content = "Deleted File"` (line 145), `isDeleted = true` (line 151), `id = appData.uniqueId ?: header.fileId` (line 132, the *safe* id fallback), `conversationId = appData.groupId!!` (line 135 — bare `!!`, latent, out of scope).
- **Happy-path require gates** (lines 159–161): `require(content != null)`, `require(appData.uniqueId != null)`, `require(appData.groupId != null)`. A null `uniqueId`/`groupId` throws here → the `catch` fallback.
- **Status-message branch** (lines 176–198): `isStatusMessage = appData.dataType == ChatProtocol.ChatStatusMessageDataType` (line 97, `= 202`). Deserializes `StatusMessageData` from `content`. **Peer-drop**: if `status.statusMessage == StatusMessage.GroupHealLocalCleanup && metadata.originalAuthor != domain` → `return null` (lines 183–187). Otherwise renders via `renderStatusMessage` and sets `isStatusMessage = true` on the model.
- **Typed-content branch** (lines 171–208): `MessageContentParser.parse(appData.dataType, content)` (line 173). For an Event (`dataType = ChatProtocol.ChatEventMessageDataType = 210`) with valid JSON, returns `MessageContent.Event(descriptor)`; the model's `content` becomes `JsonPrimitive(messageContent.displayLabel)` → the event title (lines 70 of `MessageContent.kt`: `descriptor?.title ?: UNPARSEABLE_EVENT_LABEL`), and `messageContent` is set on the model (line 288).
- **Plain-text / media + malformed-fallback branch** (lines 209–217): the `else`. Deserializes `content` as `MessageAppData`. **Crucially**, a *malformed* typed-content (parser returned null because the descriptor JSON didn't parse) also lands here — but note `MessageContentParser.parse` returns a non-null `MessageContent.Event(descriptor = null)` for a broken **event**, so a broken event does NOT hit this branch; it hits the `messageContent != null` branch with a null descriptor. The genuine "malformed typed content falls back to plain text" is: a `dataType` that the parser maps to `null` (e.g. `0` plain text, or `202`/`211`) whose content is still MessageAppData-shaped. See STOP/maintenance notes — choose the malformed case carefully.
- **Send-tag precedence** (lines 101–105): `isFailedSend = localTags?.contains(ChatProtocol.isFailedSendTag)`; `isPendingSend = ...isPendingSendTag... && !isFailedSend`. Failed wins. (Already covered in `ChatMessageStreamMapperTest`; re-asserted here for the characterization completeness, scoped to the *model* fields.)
- **The catch fallback** (lines 292–335) — **the plan-005 bug**: `id = appData.uniqueId!!` (line 304) and `conversationId = appData.groupId!!` (line 307). A message that reached the catch *because* `uniqueId`/`groupId` was null NPEs here, falls into the inner catch, returns `null` — the rescue bubble that should say "Failed to parse message from server" (line 308) never appears. The plan-005 fix changes line 304 to `appData.uniqueId ?: header.fileId` and adds an explicit null-`groupId` `return null` guard.

### Existing tests (the fixture patterns to reuse — READ both before writing)

`homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/MessageMapperDeliveryStatusTest.kt` — its `buildHeader(groupId, originalRecipientCount, transferHistoryJson)` returns a `HomebaseFile` by deserializing a raw JSON string with `OdinSystemSerializer.deserialize<HomebaseFile>(json)`. This is the **header-from-JSON** pattern. It hardcodes `originalAuthor: "sender.test"`, `dataType: 0`, `content: "{}"`.

`homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/ChatMessageStreamMapperTest.kt` — the **closer model** for this plan. It has:
- `createTestCredentialsManager()` (lines 31–41): builds a `CredentialsManager`, `setActiveCredentials(ApiCredentials.create(domain = OdinId("owner.test"), clientAccessToken = "test-token", sharedSecret = SecureByteArray(ByteArray(16))))`. The active domain is `"owner.test"`.
- `buildChatMessageHeader(localAppDataJson, fileState = "active")` (lines 57–122): raw-JSON header builder with `content` = a real `MessageAppData` JSON (`{"message":"hi","deliveryStatus":20,"isEdited":false,"version":1}`), `fileType = ChatProtocol.MessageFileType`, `dataType: 0`, `originalAuthor: "sender.test"`.
- `runTest { ... mapToMessageData(header, cm) ... assertNotNull(result) }` cases (lines 124–241), including the `isFailedSendTag`/`isPendingSendTag` precedence tests this plan re-characterizes.

**Key fixture facts that drive the new tests:**
- Active domain = `"owner.test"`; both existing fixtures set `originalAuthor: "sender.test"`. So **author ≠ active domain** by default. The `GroupHealLocalCleanup` *peer-drop* path (author ≠ domain → `return null`) is therefore the *default* for a cleanup status; to exercise the *kept* (own-cleanup) path you must set `originalAuthor` to `"owner.test"`. The new builder must let the test set `originalAuthor`.
- `content` is emitted into the JSON as a string with `"` escaped (see `escapedContent` at line 64). Your status/event JSON content must be escaped the same way.
- `result.content` for status/event branches is `messageAppData.getMessage()` → for `version == null` MessageAppData (which the status & event branches build via `MessageAppData(message = JsonPrimitive(rendered), ...)`, no `version`), `getMessage()` → `getMessageAsString()` → `message.content` (the rendered/displayLabel string). So `result.content` equals the rendered status string (status) or the event title (event).

### Convention that applies
- Tests use **FAKES not mocks** (no Mockito/MockK). The `CredentialsManager` is a real instance with test credentials — that is the established fake pattern here.
- Shared mapper tests live in `homebase-chat/src/jvmTest`. This plan adds a JVM test (the existing two mapper tests are JVM-only; `mapToMessageData` is `suspend` and the fixtures deserialize JSON, both fine on JVM). Do NOT put it in `commonTest` (the existing mapper tests are jvmTest; keep parity and avoid pulling status-string rendering — which calls `TranslationUtil.getString` / compose resources — into a multiplatform test where resource loading on non-JVM targets is untested). The status assertion below deliberately does **not** assert the exact localized string for this reason — see Test plan.
- No `Text(...)` composables are added, so the Konsist `ArchitectureTest` is not engaged.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run first) | `git diff --stat 45e2832e..HEAD -- homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/MessageMapper.kt homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/ChatMessageStreamMapperTest.kt homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/MessageMapperDeliveryStatusTest.kt` | No output (no drift). Else compare excerpts before proceeding. |
| Compile test sources (JVM) | `./gradlew :homebase-chat:compileTestKotlinJvm` | `BUILD SUCCESSFUL` |
| Run the module's JVM tests | `./gradlew :homebase-chat:jvmTest --rerun-tasks` | `BUILD SUCCESSFUL`; all green. (Before plan 005 lands, the `nullUniqueId`/`nullGroupId` cases FAIL — see Step 6.) |
| Run just this class | `./gradlew :homebase-chat:jvmTest --rerun-tasks --tests "id.homebase.chat.services.MessageMapperTest"` | `BUILD SUCCESSFUL` (post-005) |
| Confirm new file exists & is the only added source | `git status --porcelain` | Lists only the new test file + this plan + README |

## Scope
**In scope (create only this file):**
- `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/MessageMapperTest.kt` — NEW characterization test. This is the only source/test file this plan creates or edits.

**Out of scope (do NOT touch):**
- `MessageMapper.kt` — the source change is plan 005; this plan must not modify the production code. (If a test reveals the null-id NPE, that is the *expected* pre-005 failure, not a thing to fix here.)
- `ChatMessageStreamMapperTest.kt` — leave it; plan 005 adds its own null-id regression tests there. This plan's null-id cases live in the **new** file with **different method names**, so there is no duplicate-symbol clash (separate Kotlin test classes). Do not move or delete anything from `ChatMessageStreamMapperTest`.
- `MessageMapperDeliveryStatusTest.kt` — its `getDeliveryStatus` coverage stays; do not duplicate those branches here.
- `MessageContentParser`, `EventDescriptor`, `StatusMessageData`, `MessageUiModel`, `MessageAppData` — read-only references for building fixtures and assertions; do not edit.

## Steps

1. **Create the new test file** `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/MessageMapperTest.kt` with the package `id.homebase.chat.services`, the `@file:OptIn(ExperimentalUuidApi::class)` header (matching the existing two test files), and a `MessageMapperTest` class. Copy the `createTestCredentialsManager()` helper verbatim from `ChatMessageStreamMapperTest.kt` (domain `"owner.test"`).
   - Verify: `./gradlew :homebase-chat:compileTestKotlinJvm` -> `BUILD SUCCESSFUL` (empty class compiles).

2. **Add a parametrized header builder** in the new file — a single `buildHeader(...)` that the existing JSON pattern (deserialize a raw JSON `HomebaseFile`), but with parameters the branch tests need. Model the JSON body on `ChatMessageStreamMapperTest.buildChatMessageHeader` (same `keyHeader`, `serverMetadata`, etc.). Parameters (all with sensible defaults so each test sets only what it needs):
   ```kotlin
   private fun buildHeader(
       dataType: Int = 0,
       contentJson: String,                          // the RAW (unescaped) appData.content JSON
       fileState: String = "active",
       originalAuthor: String = "sender.test",       // active domain is "owner.test"
       senderOdinId: String = "sender.test",
       localAppDataJson: String? = null,
       uniqueIdJson: String = "\"${Uuid.random()}\"",// pass "null" for a null uniqueId
       groupIdJson: String = "\"${Uuid.random()}\"", // pass "null" for a null groupId
   ): HomebaseFile
   ```
   Inside, escape the content the same way the exemplar does: `val escapedContent = contentJson.replace("\"", "\\\"")` and emit `"content": "$escapedContent"`. Emit `"dataType": $dataType`, `"uniqueId": $uniqueIdJson`, `"groupId": $groupIdJson` (no surrounding quotes in the template — the param supplies them or `null`), `"originalAuthor": "$originalAuthor"`, `"senderOdinId": "$senderOdinId"`, `"fileState": "$fileState"`, `"localAppData": ${localAppDataJson ?: "null"}`, `"fileType": ${ChatProtocol.MessageFileType}`. Keep `originalRecipientCount: 0` and `transferHistory: null` (so delivery status is Read/Sent — irrelevant to these branch assertions). Return `OdinSystemSerializer.deserialize<HomebaseFile>(json)`.
   - Verify: `./gradlew :homebase-chat:compileTestKotlinJvm` -> `BUILD SUCCESSFUL`.

3. **Add the happy-path branch tests** (plain text, media, deleted, typed-content event). See Test plan for the exact cases and assertions. Each is a `@Test fun ... = runTest { val cm = createTestCredentialsManager(); val header = buildHeader(...); val result = mapToMessageData(header, cm); assertNotNull(result); ... }`.
   - Verify: `./gradlew :homebase-chat:compileTestKotlinJvm` -> `BUILD SUCCESSFUL`.

4. **Add the status-message + peer-drop tests.** (a) An own-authored `GroupHealLocalCleanup` (`originalAuthor = "owner.test"`) → kept, `result != null`, `result.isStatusMessage == true`. (b) A peer-authored `GroupHealLocalCleanup` (`originalAuthor = "sender.test"`, the default) → `result == null` (the peer-drop). (c) A non-cleanup status (e.g. `ConversationStarted`) authored by anyone → kept, `isStatusMessage == true`.
   - Verify: `./gradlew :homebase-chat:compileTestKotlinJvm` -> `BUILD SUCCESSFUL`.

5. **Add the malformed-typed-content + send-tag-precedence tests.** (a) malformed typed content falls back to plain text — see Test plan for the *correct* construction (a parser-null dataType with MessageAppData-shaped content; do NOT use a broken event, which yields `Event(descriptor=null)` not a fallback). (b) `isFailedSend` precedence over `isPendingSend` on the model (`result.isFailedSend == true`, `result.isPendingSend == false`).
   - Verify: `./gradlew :homebase-chat:compileTestKotlinJvm` -> `BUILD SUCCESSFUL`.

6. **Add the plan-005 regression tests** (`nullUniqueId`, `nullGroupId`). These are the safety net for plan 005. Add them last and run the suite.
   - Verify (BEFORE plan 005 lands): `./gradlew :homebase-chat:jvmTest --rerun-tasks --tests "id.homebase.chat.services.MessageMapperTest"` — the `nullUniqueId_doesNotThrow_usesFileIdFallback` case is **EXPECTED TO FAIL** against unpatched source (the fallback's `appData.uniqueId!!` NPEs → returns null → `assertNotNull` fails). The `nullGroupId_returnsNull` case may **pass even pre-005** (the NPE path also returns null, which is what it asserts) — that is acceptable; its value is locking the *post-005* explicit-drop behaviour. Record which cases failed and STOP per the STOP conditions only if a *non-null-id* case fails. Once plan 005 is applied, re-run: all cases pass.

7. **Update `plans/README.md`** — mark this plan's row Done. If `plans/README.md` does not exist, create it with a header `| Plan | Title | Status |` + separator and add `| 023 | MessageMapper characterization test | Done |`.
   - Verify: `git status --porcelain` lists only `MessageMapperTest.kt`, `plans/023-messagemapper-test.md`, and `plans/README.md`.

## Test plan

New file: `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/MessageMapperTest.kt`. Model after `ChatMessageStreamMapperTest.kt` (`createTestCredentialsManager` + `runTest` + raw-JSON header). Imports: `id.homebase.api.client.auth.ApiCredentials`, `id.homebase.api.client.auth.CredentialsManager`, `id.homebase.api.client.drives.HomebaseFile`, `id.homebase.api.common.OdinId`, `id.homebase.api.common.SecureByteArray`, `id.homebase.api.serialization.OdinSystemSerializer`, `id.homebase.chat.services.content.MessageContent`, `kotlin.test.Test`, `assertEquals`, `assertNotNull`, `assertNull`, `assertTrue`, `kotlin.time.Clock`, `kotlin.uuid.ExperimentalUuidApi`, `kotlin.uuid.Uuid`, `kotlinx.coroutines.test.runTest`.

Cases (each `@Test fun ... = runTest { ... }`):

1. **`plainText_mapsContentAndNotDeleted`** — `buildHeader(dataType = 0, contentJson = """{"message":"hi","deliveryStatus":20,"isEdited":false,"version":1}""")`. Assert `assertNotNull(result)`, `assertEquals("hi", result.content)`, `assertEquals(false, result.isDeleted)`, `assertEquals(false, result.isStatusMessage)`, `assertNull(result.messageContent)`.

2. **`media_hasMoreTrue_whenDefaultPayloadPresent`** *(optional but recommended)* — media is the same plain-text path with a payload present. The `hasMore` flag is driven by a payload whose `key == ChatProtocol.DefaultPayloadKey`. The existing builders emit `"payloads": []`. If wiring a payload into the JSON is awkward, SKIP this case and instead assert the plain-text default: `assertEquals(false, result.hasMore)` in case 1. (Keep the suite green over chasing this; note the skip in the file with a comment.)

3. **`deleted_returnsDeletedFileBubble`** — `buildHeader(contentJson = """{}""", fileState = "deleted")`. Assert `assertNotNull(result)`, `assertTrue(result.isDeleted)`, `assertEquals("Deleted File", result.content)`. (This pins the deleted branch's `content` literal at line 145 and `isDeleted = true` at line 151.)

4. **`typedEvent_setsMessageContentAndTitleAsContent`** — build a valid `EventDescriptor` JSON and send it as a typed event:
   ```kotlin
   val eventJson = """{"title":"Standup","startUtcMs":1000,"timezone":"UTC","schemaVersion":2}"""
   val header = buildHeader(dataType = ChatProtocol.ChatEventMessageDataType, contentJson = eventJson)
   ```
   Assert `assertNotNull(result)`, `assertEquals("Standup", result.content)` (the displayLabel is the title — `MessageContent.Event.displayLabel` = `descriptor?.title`), and `assertTrue(result.messageContent is MessageContent.Event)`, and `assertEquals("Standup", (result.messageContent as MessageContent.Event).descriptor?.title)`.

5. **`statusMessage_ownCleanup_kept`** — own-authored cleanup status is kept:
   ```kotlin
   val statusJson = """{"statusMessage":"GroupHealLocalCleanup","groupHealCleanup":{"cleanedUpMain":true,"cleanedUpAdmin":false}}"""
   val header = buildHeader(
       dataType = ChatProtocol.ChatStatusMessageDataType,
       contentJson = statusJson,
       originalAuthor = "owner.test",   // == active domain → NOT a peer copy
       senderOdinId = "owner.test",
   )
   ```
   Assert `assertNotNull(result)`, `assertTrue(result.isStatusMessage)`. Do **not** assert the exact rendered string (it goes through `TranslationUtil.getString` / compose resources); assert only `result.content.isNotBlank()`.

6. **`statusMessage_peerCleanup_dropped`** — identical status JSON but `originalAuthor = "sender.test"` (the default, ≠ active domain `"owner.test"`). Assert `assertNull(result)` — this is the `GroupHealLocalCleanup` peer-drop at lines 183–187. **This is the highest-value status assertion** — it locks the privacy/heal-correctness rule that a peer's "I cleaned up my copy" marker must never render in our chat.

7. **`statusMessage_nonCleanup_kept`** — `"""{"statusMessage":"ConversationStarted"}"""`, default author. Assert `assertNotNull(result)`, `assertTrue(result.isStatusMessage)`, `result.content.isNotBlank()`. (Proves non-cleanup statuses are not subject to the peer-drop.)

8. **`malformedTypedContent_fallsBackToPlainText`** — the fallback path (parser returns `null` → `else` branch deserializes as `MessageAppData`). **Construct it correctly:** a broken *event* does NOT exercise this (the parser returns `Event(descriptor=null)`, a non-null `MessageContent`, which routes to the `messageContent != null` branch). The genuine fallback is a `dataType` the parser maps to `null` whose content is MessageAppData-shaped — i.e. plain `dataType = 0` is already the fallback path, so to make "malformed" meaningful, assert that **a `dataType = 0` message whose content is NOT a valid `MessageAppData` JSON ends up in the catch fallback** with the "Failed to parse message from server" rescue bubble:
   ```kotlin
   val header = buildHeader(dataType = 0, contentJson = """{"this":"is not MessageAppData but valid JSON that lacks message"}""")
   val result = mapToMessageData(header, cm)
   assertNotNull(result)
   ```
   Note: `MessageAppData` has all-defaulted fields, so a JSON object with unknown keys may deserialize successfully to a default `MessageAppData` (message = `""`) rather than throwing — in which case `result.content == ""` and it does NOT reach the catch. **Determine the actual behaviour empirically**: run with the above content; if `result` is non-null with empty content, assert `assertNotNull(result)` only (the message is not dropped — that is the contract being characterized: malformed-but-lenient content still produces a bubble, never a vanish). If it reaches the catch, assert `assertEquals("Failed to parse message from server", result.content)`. Either way the load-bearing assertion is `assertNotNull(result)` — **the message must not silently vanish.** Document in a code comment which path it actually took on this codebase.

9. **`failedSend_winsOverPending_onModel`** — `localAppDataJson = """{"tags": ["${ChatProtocol.isPendingSendTag}", "${ChatProtocol.isFailedSendTag}"]}"""`, plain-text content. Assert `assertNotNull(result)`, `assertTrue(result.isFailedSend)`, `assertEquals(false, result.isPendingSend)`. (Re-characterizes the precedence at the model level; `ChatMessageStreamMapperTest` already covers it, but this keeps the single-file branch matrix complete.)

10. **`nullGroupId_returnsNull` (plan-005 regression)** — `buildHeader(contentJson = """{"message":"hi","version":1}""", groupIdJson = "null")`. Assert `assertNull(result)`. Pre-005 the catch's `appData.groupId!!` NPEs and the function returns null via the inner catch → this asserts null and may pass pre-005; post-005 the explicit null-`groupId` guard returns null with a clear log. Either way the contract is: **a message with no groupId does not crash and does not appear.**

11. **`nullUniqueId_doesNotThrow_usesFileIdFallback` (plan-005 regression — the one that FAILS pre-005)** — capture `val header = buildHeader(contentJson = """{"message":"hi","version":1}""", uniqueIdJson = "null")` then `val fileId = header.fileId`. The happy path throws at `require(appData.uniqueId != null)` (line 160) → catch fallback. **Post-005** the fallback uses `id = appData.uniqueId ?: header.fileId`, so: `assertNotNull(result)` (rescue bubble produced, not dropped), `assertEquals(fileId, result.id)`. **Pre-005** the fallback's `appData.uniqueId!!` throws → inner catch → returns null → `assertNotNull` FAILS. This is the intended red→green for plan 005. Add a `// EXPECTED TO FAIL until plan 005 lands` comment above this test.

Verify command: `./gradlew :homebase-chat:jvmTest --rerun-tasks --tests "id.homebase.chat.services.MessageMapperTest"` -> `BUILD SUCCESSFUL` once plan 005 is applied; pre-005, exactly the `nullUniqueId` case fails (and only that case).

## Done criteria
- [ ] `homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/MessageMapperTest.kt` exists with the package `id.homebase.chat.services` and the cases above (plain text, deleted, typed event, status own-cleanup kept, status peer-cleanup dropped, status non-cleanup kept, malformed fallback, failed-over-pending, nullGroupId, nullUniqueId).
- [ ] `./gradlew :homebase-chat:compileTestKotlinJvm` -> `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-chat:jvmTest --rerun-tasks` -> after plan 005 is applied, `BUILD SUCCESSFUL` with all new tests green; no existing test (`MessageMapperDeliveryStatusTest`, `ChatMessageStreamMapperTest`) regressed.
- [ ] Pre-005, exactly the `nullUniqueId_doesNotThrow_usesFileIdFallback` case fails (red), and no other new case fails — recorded in the executor's report.
- [ ] No production source file was modified (`git status --porcelain` shows no change under `src/commonMain`).
- [ ] `git status --porcelain` lists only `MessageMapperTest.kt`, `plans/023-messagemapper-test.md`, `plans/README.md`.
- [ ] This plan's row in `plans/README.md` is marked Done.

## STOP conditions
- **Drift:** the drift-check `git diff --stat 45e2832e..HEAD` shows any of the three in-scope-reference files changed AND the Current-state excerpts no longer match live code — STOP and report.
- **A non-null-id case fails:** if any case *other than* `nullUniqueId` fails (pre- or post-005) — e.g. the peer-drop returns non-null, the deleted bubble's content isn't `"Deleted File"`, the event title doesn't surface — STOP. That means a documented branch behaviour was characterized wrong (or the source drifted); report the actual vs expected before "fixing" the test to match, because the point of a characterization test is to encode *true current behaviour*, not a guess.
- **`requireActiveDomain()` throws in setup:** if `createTestCredentialsManager` doesn't satisfy `requireActiveDomain()` (NPE/exception at line 93) — STOP; the credentials fake is wired wrong (compare byte-for-byte against `ChatMessageStreamMapperTest`).
- **Status JSON won't deserialize:** if `OdinSystemSerializer.deserialize<StatusMessageData>` fails on the enum name (`"GroupHealLocalCleanup"` / `"ConversationStarted"`) — STOP and check the exact `@Serializable enum class StatusMessage` member spelling in `StatusMessage.kt` (the serial name is the enum constant name unless a `@SerialName` overrides it — none does at commit 45e2832e).
- **Malformed-fallback case is ambiguous:** if case 8 cannot be made deterministic (you can't tell whether lenient deserialization or the catch fires), fall back to asserting only `assertNotNull(result)` and document the observed path in a comment. Do NOT delete the case — the non-vanish contract is the point.

## Maintenance notes
- This plan deliberately does **not** assert the exact localized status string (it routes through `TranslationUtil.getString` + compose resources). If a future change makes those resources reliably loadable in jvmTest, a follow-up could tighten case 5/7 to assert the rendered text. Until then, `isNotBlank()` is the right altitude.
- The deleted branch's `conversationId = appData.groupId!!` (line 135) is a latent NPE of the same class plan 005 fixes on the catch path — but it is **out of scope** here and in plan 005. A reviewer should note that case 3 (`deleted_returnsDeletedFileBubble`) uses a valid `groupId`, so it does NOT exercise that latent `!!`; a future plan that hardens the deleted branch should add a `deleted + null groupId` case here.
- A reviewer should scrutinize: (a) that the peer-drop test (case 6) really has `originalAuthor != "owner.test"` and the own-cleanup test (case 5) really has `== "owner.test"` — flipping them silently inverts the assertion; (b) that case 8's malformed construction matches whatever lenient/strict behaviour the codebase actually has (the comment must state which path fired); (c) that the `nullUniqueId` case (11) is the *only* expected pre-005 red, so this suite can be committed before plan 005 without blocking CI only if 005 lands in the same change-set — if landing 023 strictly first on a shared branch, mark case 11 `@Ignore("un-ignore when plan 005 lands")` with a TODO, OR land both together. Prefer landing both together (005 + 023 in one PR) so CI is never red.
- Deferred follow-up: once plan 005 lands, remove any `@Ignore`/`EXPECTED TO FAIL` markers from case 11 and confirm green.
