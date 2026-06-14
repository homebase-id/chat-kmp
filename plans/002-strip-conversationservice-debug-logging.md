# Plan 002: Strip the "DO NOT MERGE" debug instrumentation that logs AES keys and participant PII

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/GroupHealService.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationServiceCollaborators.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/MethodAudit.kt CONVERSATION_SERVICE_DEBUG.md`. If any in-scope file changed since this plan was written, compare the Current state excerpts to live code first; on mismatch, STOP.

## Drift note (read before starting)

This plan was written against live code at commit `45e2832e` (which equals HEAD at authoring time). Two corrections to the upstream finding/recipe, discovered by opening the files:

1. **A FOURTH file is in scope that the finding's scope list omitted:**
   `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationServiceCollaborators.kt`.
   The `GroupHealConversationOps` interface there declares `writeOrReplaceConversationPlaceholder`
   (line 75–82) and `writeOrReplaceAdminPlaceholder` (line 84–89) with a trailing
   `audit: MethodAudit,` parameter (lines 81 and 88). `MethodAudit` is the class being deleted, and
   the `ConversationService` overrides of those two methods carry the same param. **If the interface
   param is not removed in lockstep with the override params, the module will not compile** (override
   signature mismatch + unresolved `MethodAudit` reference). This file is therefore added to scope.

2. **DO NOT remove the `import id.homebase.api.sync.database.QueryBatch` line**, even though the
   in-file banner (ConversationService.kt line 74) and CONVERSATION_SERVICE_DEBUG.md say to. `QueryBatch`
   is used by **real logic** at `ConversationService.kt:1737` inside `tryHardDeleteUnrecoverableOwnHeader`
   (the own-drive corrupt-header hard-delete path — NOT audit code), in addition to two audit-only
   diagnostic counts at lines 1903 and 2030. After removing the audit blocks the import is still needed
   by line 1737. The recipe's "remove the import IF only the audit used it" is correctly conditional —
   the condition is FALSE here. Verify with the grep in Step 8 before touching the import.

Otherwise every cited line number matched live code. `codeMatchedFinding=true` overall (the cited
markers, aesKey log lines, and ParticipantsAudit sites all exist exactly as described); the two items
above are scope/recipe refinements, not a code mismatch.

## Status
- Priority: P1
- Effort: M
- Risk: LOW
- Depends on: none
- Category: security (also tech-debt, docs)
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters

The conversation services carry a self-described "TEMPORARY DEBUG INSTRUMENTATION — DO NOT MERGE"
harness that landed on `main`. It writes raw conversation **AES keys** (`keyHeader.aesKey.unsafeBytes.toBase64()`)
and **recipient domain names (PII)** to the on-disk `homebase.log` via `Logger.d`/`Logger.i`. The
default log severity is `Severity.Verbose` (`LoggerConfig.kt:30`) and **no** entry point overrides it
(Android `MainApplication.kt:93`, Desktop `Main.kt:70`, iOS `MainViewController.kt:71` all call
`LoggerConfig.initialize(...)` with no `minimumSeverity`), so Debug and Info lines are persisted to the
rolling file logger **in release builds**. That file is routinely exported by users for support and is
mirrored into Crashlytics breadcrumbs (Info+) — meaning per-conversation encryption keys and the full
participant list of every group leak into logs and crash reports. This plan removes the scaffolding
with zero behaviour change, eliminating the key/PII exposure and the "DO NOT MERGE" debt.

## Current state

All paths below are absolute-relative to repo root `/Users/biswa/Documents/GitHub/chat-kmp`.

### 1. `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/MethodAudit.kt` (DELETE)
The whole file is the audit harness. Class `MethodAudit` (line 21) with `start/pre/step/post/info`
(lines 25–31) all calling `Logger.i` **unconditionally** (no debug/build gate), plus
`checkFail/checkWarn/threw/finish` (lines 34–61). `companion object { const val TAG = "ConvoAudit" }`.
Nothing outside `homebase-chat` references it (grep confirmed only the 4 in-scope files + stale
`homebase-core/build/...` framework headers, which are generated artifacts — ignore them).

### 2. `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt` (2525 lines)
Role: the central conversation create/update/delete/leave/heal-placeholder service.

- **Banner box** lines 61–76 ("TEMPORARY DEBUG INSTRUMENTATION HELPER — DO NOT MERGE") sits directly
  above `class ConversationService(` (line 77). Delete the banner; keep the class.
- **`import id.homebase.api.sync.database.QueryBatch`** line 33 — KEEP (used at real-logic line 1737).
- **Fenced audit blocks**: 40+ `// ---- DEBUG instrumentation ----` / `// ---- end DEBUG ----` fence
  pairs. Map every one with the grep in "Commands". Most fence a clean block of `audit.*` /
  `Logger.*(tag = "ParticipantsAudit")` lines you can delete wholesale. **But several fences interleave
  with real control flow** — see the traps below.
- **`val audit = MethodAudit("...")`** constructed in ~28 methods (grep `val audit = MethodAudit(`).
- **AES-key leak lines** (5 active `Logger.d` — remove the `aesKey=...toBase64()` interpolation; line
  1020 is already commented out, LEAVE IT):
  - `871` `leaveGroup START: ... aesKey=${leaveFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}`
  - `1185` `updateConversationInternal: ... aesKey=${conversationFile.keyHeader.aesKey.unsafeBytes.toBase64()} ivLen=... keyLen=...`
  - `1298` `updateConversationInternal PRE-REQUEST: ... aesKey=${keyHeader.aesKey.unsafeBytes.toBase64()} versionTag=...`
  - `1331` `updateConversationInternal POST-ENCRYPT: ... aesKey=${keyHeader...toBase64()} requestKeyHeader=${request.keyHeader?...toBase64()}`
  - `1929` `deleteConversation: ... aesKey=${deleteFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}`
- **`ParticipantsAudit` PII lines**: `Logger.i/w(tag = "ParticipantsAudit")` at 164, 170, 212, 276,
  282, 415, 419, 479, 487, 494, 1139, 1164, 1172, 1179, 1389, 1396, 1403 (grep `ParticipantsAudit`).
  These dump recipient domain names. Delete them all.
- **`audit: MethodAudit` parameters** on real private/override methods (signatures must lose the param):
  - `tryHardDeleteUnrecoverableOwnHeader(...)` line 1727
  - `writeOrReplaceConversationPlaceholder(...)` (override) line 1796
  - `writeOrReplaceAdminPlaceholder(...)` (override) line 1847
  - Call sites passing `audit = audit,`: lines 1676 and 1698 (inside `recoverConversation`).

#### Traps in ConversationService.kt (real logic interleaved with audit)
The audit is NOT always a pure pre/post wrapper. These spots require statement-level edits, keeping the
real code and its `throw`/`error`/`return`:

- **createConversation revive** lines 246–259: real call is
  `runCatching { updateConversationInternal(...) }.onSuccess { audit.checkPass("revive") }.onFailure { e -> audit.threw(...); audit.finish(...); throw e }`.
  After removal the call becomes a plain `updateConversationInternal(...)` (the `runCatching` existed
  only to feed the audit; the original semantics were "call it, and if it throws, propagate"). **Keep
  the throw-on-failure behaviour**: simplest safe edit is just `updateConversationInternal(...)` with no
  wrapper — an uncaught exception propagates identically. Verify the surrounding `if (needsRevive)` /
  `else` / `return CreateConversationResult(...)` (lines 240, 260, 264–265) stay intact.
- **deleteConversation STEP 1/STEP 2** lines 1963–1994: `runCatching { outboxSync.tryEnqueue(...) }`
  and `runCatching { updateConversationInternal(...) }` each followed by `.onFailure { ...; throw e }`.
  Original semantics: call, propagate on throw. After removal, reduce to the bare
  `outboxSync.tryEnqueue(DeleteFilesByGroupIdOutboxRequest(...))` and
  `updateConversationInternal(...)` calls (drop the `val step1 =`/`val step2 =`, the `runCatching`,
  and the `onSuccess/onFailure` audit handlers). The bare calls throw on failure exactly as the
  `onFailure { throw e }` did.
- **updateConversationTags** lines 2069–2076: the audit `if (file == null) { audit.checkFail(...); audit.finish(...) }`
  block at 2071–2074 does **NOT** return. The REAL guard is line 2076 `if (file == null) error("Conversation not found: $conversationId")`.
  Delete only the audit `if` block (2069–2075 fence); **keep line 2076**.
- **clearConversation** lines 2039–2056: real body is the single
  `val enqueued = outboxSync.tryEnqueue(DeleteFilesByGroupIdOutboxRequest(...))` (lines 2044–2049).
  Everything fenced around it is audit; remove the fences and the now-unused `val enqueued`/`outboxBefore`
  if nothing else reads them (check after edit — `enqueued` was only read by the audit `check`).
- **leaveGroup / updateConversationInternal**: large methods with several POST-verification fences
  (e.g. 996–1012, 1149–1181, 1366–1408). These are audit-only blocks — but they read locals
  (`postLeaveFile`, readback files) computed *inside* the fence, so deleting the whole fence removes
  both the read and its logging together. Confirm no code *after* the fence references a local declared
  *inside* it before deleting (the audit POST blocks are self-contained — they declare-and-log).

### 3. `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/GroupHealService.kt` (753 lines)
Role: receive-side group-conversation recovery.

- **NOT cleanly fenced.** `val audit = MethodAudit("healGroupDistribution")` is constructed at line 150
  (inside a fence) but the `audit.checkFail/finish/check/info/step/threw/pre/checkWarn/checkPass` calls
  are **scattered through the live method body, mostly outside any fence** (lines 155–156, 414–415,
  421–422, 433–434, 452, 455, 457, 468–470, 499, 505, 524, 533–534, 554, 565–567, 569, 591, 615,
  634, 645, 652–653, 668, 682–684, 691, 698, 720, 724–726, 728, 739–741). These must be deleted
  statement-by-statement.
- **TRAP — audit calls precede a real `throw`/`return`**: e.g. lines 155–156
  `audit.checkFail("isGroupGuard", ...); audit.finish("REJECTED at guard")` sit immediately before
  line 157 `throw IllegalStateException(...)`. Lines 414–416, 421–423, 433–435 each do
  `audit.*; audit.finish(...); return`. **Delete only the `audit.*` lines; keep the `throw`/`return`.**
- **`handleIncomingHealRequest`** (starts ~line 404) constructs `val audit = MethodAudit(...)` at line
  409 with NO opening fence comment. Delete that line and every `audit.*` call in the method.
- **`audit: MethodAudit` parameter** on `selfDestructHealMessage(...)` line 716 — remove the param;
  update its 2 call sites at lines 533 (`selfDestructHealMessage(healMessageFileId, audit, step = 2)`)
  and 691 (`selfDestructHealMessage(healMessageFileId, audit, step = 4)`) to drop the `audit` arg.
- **Call sites passing `audit = audit,`** into the ConversationService overrides: lines 599 and 621.
  Drop the `audit = audit,` named arg.
- These audit removals must NOT touch the genuine `Logger.i/w/d/e` lines in this file (e.g. 172, 213,
  246, 250, 260, 265, 267, 270, 275, 287, 290, 300, 305, 307, 310, 315, 324, 327, 334, 511, 525,
  553, 570, 586, 610, 640, 693) — those are the real heal logs and stay (they log domain names too,
  but that's pre-existing operational logging out of scope for this plan; see Maintenance notes).

### 4. `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationServiceCollaborators.kt`
Interface `GroupHealConversationOps`. Remove the `audit: MethodAudit,` param from
`writeOrReplaceConversationPlaceholder` (line 81) and `writeOrReplaceAdminPlaceholder` (line 88).
No import of `MethodAudit` exists here (same package), so no import line to remove. **Edit this in the
SAME step as the ConversationService override signatures (Step 5) so the build is never broken.**

### 5. `CONVERSATION_SERVICE_DEBUG.md` (repo root) (DELETE)
Documents the harness; titled "Status: temporary debug instrumentation — DO NOT MERGE."

### Convention that applies
This is logging/scaffolding removal only — no UI, no strings, no flows. The relevant convention is the
debugging-discipline rule in `CLAUDE.md` ("do not ship a workaround that hides the symptom"): here we are
*removing* instrumentation that was added for a since-resolved bug hunt, not hiding a live symptom — the
heal/create/delete logic and its genuine `Logger.*` operational logs are preserved verbatim.

### Entry points proving the leak reaches release (read-only context, OUT of scope)
- `homebase-common/src/commonMain/kotlin/id/homebase/core/logging/LoggerConfig.kt:30` —
  `minimumSeverity: Severity = Severity.Verbose` (default).
- `androidApp/src/main/kotlin/id/homebase/feed/MainApplication.kt:93`,
  `desktopApp/src/jvmMain/kotlin/id/homebase/app/Main.kt:70`,
  `homebase-core/src/nativeMain/kotlin/id/homebase/core/MainViewController.kt:71` — all call
  `LoggerConfig.initialize(...)` with NO `minimumSeverity` arg, so Debug+Info hit the disk log.
  This plan does not change the severity config (that is a separate hardening decision); removing the
  key/PII log lines is the targeted fix.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift baseline | `git diff --stat 45e2832e..HEAD -- homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/GroupHealService.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationServiceCollaborators.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/MethodAudit.kt CONVERSATION_SERVICE_DEBUG.md` | empty output (no drift) |
| Map all ConversationService fences | `grep -n "// ---- DEBUG instrumentation\|// ---- end DEBUG" homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt` | 40+ matched line pairs to remove |
| Map all audit constructions | `grep -n "val audit = MethodAudit(" homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/GroupHealService.kt` | the list to delete |
| Compile (JVM/Desktop) | `./gradlew :homebase-chat:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile (Android) | `./gradlew :homebase-chat:compileAndroidMain` | `BUILD SUCCESSFUL` |
| Compile (iOS, macOS host only) | `./gradlew :homebase-chat:compileKotlinIosSimulatorArm64` | `BUILD SUCCESSFUL` (skip + note if not on macOS) |
| Run chat tests | `./gradlew :homebase-chat:jvmTest` | `BUILD SUCCESSFUL`, no failures |
| Final residue grep (must be empty) | `grep -rn "MethodAudit\|ConvoAudit\|ParticipantsAudit\|unsafeBytes.*toBase64\|aesKey.*toBase64" homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/` | no output |
| QueryBatch still used (must be non-empty before touching import) | `grep -n "QueryBatch" homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt` | line 1737 (real logic) remains |
| Confirm doc gone | `test -f CONVERSATION_SERVICE_DEBUG.md && echo PRESENT || echo GONE` | `GONE` |
| Status check | `git status --porcelain` | only the 5 in-scope files (+ plans/) |

## Scope

**In scope (modify):**
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt` — remove banner, all audit blocks/calls, aesKey/ParticipantsAudit log lines, audit params; KEEP QueryBatch import.
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/GroupHealService.kt` — remove all `audit.*` calls + `val audit = MethodAudit(...)` + `selfDestructHealMessage` audit param/args.
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationServiceCollaborators.kt` — remove `audit: MethodAudit,` param from the 2 interface methods. (Added by Drift note 1.)

**In scope (delete):**
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/MethodAudit.kt`
- `CONVERSATION_SERVICE_DEBUG.md`

**Out of scope (do NOT touch):**
- `homebase-common/.../logging/LoggerConfig.kt` — severity default is a separate hardening call; one-line aesKey removal is the targeted fix here.
- `androidApp/.../MainApplication.kt`, `desktopApp/.../Main.kt`, `homebase-core/.../MainViewController.kt` — entry-point severity overrides are out of scope.
- `ConversationServiceTestFixture.kt` — references `GroupHealConversationOps` only in a doc comment (line 147); it casts the real service, does not re-implement the interface, so no signature change reaches it. Do not edit.
- The genuine `Logger.i/w/d/e` operational logs in GroupHealService.kt / ConversationService.kt — pre-existing, not audit, behaviour-neutral to keep.
- Any create/update/delete/leave/heal **logic** — this is logging/scaffolding removal ONLY.

## Steps

> Work file-by-file. After each step run the per-module compile so the build is never left broken.
> Open each region in your editor and read it before deleting — do not delete by line number alone
> (line numbers shift as you edit; anchor on the fence comments and `audit.`/`MethodAudit` text).

1. **Drift check.** Run the drift baseline command. If output is non-empty, compare live code to the
   Current-state excerpts above; on any mismatch, STOP.

2. **ConversationService.kt — remove the banner.** Delete lines 61–76 (the box-drawing comment block
   ending at `╚════...╝`), leaving `class ConversationService(` as the first line after the imports'
   blank line. Do NOT delete the `import id.homebase.api.sync.database.QueryBatch` (line 33).
   Verify: `./gradlew :homebase-chat:compileKotlinJvm` → still compiles (audit blocks still present and
   valid at this point) → `BUILD SUCCESSFUL`.

3. **ConversationService.kt — remove the AES-key interpolations from the 5 active `Logger.d` lines**
   (871, 1185, 1298, 1331, 1929). Remove only the `aesKey=...toBase64()` (and the
   `requestKeyHeader=...toBase64()` at 1331) segments. You MAY keep a minimal, non-sensitive
   tail if useful — e.g. `conversationId`, `isEncrypted`, `ivLen`, `keyLen` (lengths are not secret),
   `versionTag`. Example for line 1185:
   `Logger.d { "updateConversationInternal: conversationId=$conversationId isEncrypted=${conversationFile.fileMetadata.isEncrypted} ivLen=${conversationFile.keyHeader.iv.size} keyLen=${conversationFile.keyHeader.aesKey.unsafeBytes.size}" }`
   (note: `.size` is a length, not the key bytes — acceptable). Leave the already-commented line 1020 as-is.
   Verify: `grep -n "aesKey.*toBase64\|unsafeBytes.*toBase64" homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt` → only commented line 1020 (prefixed `//`) remains, OR no output. Then `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`.

4. **ConversationService.kt — remove all `ParticipantsAudit` log lines.** For each
   `Logger.i(tag = "ParticipantsAudit") { ... }` and `Logger.w(tag = "ParticipantsAudit") { ... }`
   (and any single-line `Logger.w(tag = "ParticipantsAudit") { "..." }`), delete the whole statement.
   Several sit inside `if (...) { Logger.w(tag="ParticipantsAudit"){...} }` guards (e.g. 169–174,
   281–285) — delete the guard `if` too when its only body was the audit log (confirm by reading; do
   not delete an `if` that also gates real code). Verify:
   `grep -c "ParticipantsAudit" homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt` → `0`. Then `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`.

5. **ConversationService.kt + ConversationServiceCollaborators.kt — remove the `audit: MethodAudit`
   parameters together (single atomic edit set).** In ConversationServiceCollaborators.kt remove
   `audit: MethodAudit,` from `writeOrReplaceConversationPlaceholder` (line 81) and
   `writeOrReplaceAdminPlaceholder` (line 88). In ConversationService.kt remove the same trailing param
   from the overrides at lines 1796 and 1847, and from `tryHardDeleteUnrecoverableOwnHeader` (line 1727,
   private — no interface), and remove the call-site args `audit = audit,` at lines 1676 and 1698. You
   will still have `audit.*` references inside those three method bodies and inside `recoverConversation`
   — leave them for now; this step only fixes signatures so the param threading is gone. Do NOT compile
   between the two files — make both edits, THEN verify:
   `./gradlew :homebase-chat:compileKotlinJvm` (it will likely still fail on remaining `audit.*` uses —
   that is expected; the goal of this step is only that no `audit: MethodAudit` *parameter* remains).
   Confirm param removal: `grep -rn "audit: MethodAudit" homebase-chat/src/commonMain/` → no output.

6. **ConversationService.kt — remove every fenced audit block and `val audit = MethodAudit(...)`.**
   Walk the `grep -n "// ---- DEBUG instrumentation\|// ---- end DEBUG"` list top-to-bottom. For each
   fence pair, read the enclosed lines and delete them — UNLESS the block interleaves real logic, in
   which case apply the trap-specific edits from "Traps in ConversationService.kt" above
   (createConversation revive 246–259; deleteConversation 1963–1994; updateConversationTags 2069–2076
   keeping line 2076; clearConversation 2039–2056). Also delete every remaining bare `audit.*(...)`
   statement and every `val audit = MethodAudit("...")` line (including the unfenced ones inside the
   placeholder/recover/tryHardDelete methods). After this step there must be zero `audit.` and zero
   `MethodAudit` tokens in the file. Re-check `clearConversation` and any method whose only use of a
   local (`enqueued`, `outboxBefore`, `step1`, `step2`, `postFile`, etc.) was the audit — remove now-unused
   `val`s to avoid `unused variable` warnings (Konsist/lint won't fail on these but keep it clean).
   Verify: `grep -c "MethodAudit\|audit\." homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt` → `0`. Then `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`.

7. **GroupHealService.kt — remove all audit usage.** Delete the two `val audit = MethodAudit(...)`
   constructions (lines 150 and 409) and every `audit.*` call listed in the Current-state section,
   deleting ONLY the audit statements and preserving each adjacent `throw`/`return`/real call (traps:
   155–157, 414–417, 421–424, 433–436). Remove the `audit: MethodAudit,` param from
   `selfDestructHealMessage` (line 716) and drop the `audit` argument at its call sites (lines 533, 691)
   and the `audit = audit,` named args at lines 599, 621. The now-empty `// ---- DEBUG instrumentation ----`
   / `// ---- end DEBUG ----` fences in this file (e.g. 149/152, 335/343) should be deleted too. Keep
   all genuine `Logger.*` lines. Verify:
   `grep -c "MethodAudit\|audit\b" homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/GroupHealService.kt` → `0`. Then `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL`.

8. **Delete `MethodAudit.kt`.** `git rm homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/MethodAudit.kt`
   (or delete the file). Confirm QueryBatch is still referenced before touching imports:
   `grep -n "QueryBatch" homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt`
   → line 1737 present → therefore KEEP the `import ...QueryBatch` (do nothing to line 33). Verify:
   `./gradlew :homebase-chat:compileKotlinJvm` → `BUILD SUCCESSFUL` (no unresolved `MethodAudit`).

9. **Delete the doc.** `git rm CONVERSATION_SERVICE_DEBUG.md`. Verify:
   `test -f CONVERSATION_SERVICE_DEBUG.md && echo PRESENT || echo GONE` → `GONE`.

10. **Full module gate.** Run all three (skip iOS if not on macOS, and note it):
    `./gradlew :homebase-chat:compileKotlinJvm :homebase-chat:compileAndroidMain` → `BUILD SUCCESSFUL`;
    `./gradlew :homebase-chat:compileKotlinIosSimulatorArm64` (macOS only) → `BUILD SUCCESSFUL`.
    Then `./gradlew :homebase-chat:jvmTest` → `BUILD SUCCESSFUL`, no failures.

11. **Final residue + status.** Run the residue grep
    (`grep -rn "MethodAudit\|ConvoAudit\|ParticipantsAudit\|unsafeBytes.*toBase64\|aesKey.*toBase64" homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/`) → no output.
    `git status --porcelain` → only the 5 in-scope paths (2 deleted, 3 modified) plus this plan/README.

12. **Update `plans/README.md`** — mark this plan's row done (create the README if it does not yet
    exist; see Done criteria).

## Test plan

No new tests. This is pure scaffolding/logging removal with no behaviour change, so the existing suite
is the regression net: `./gradlew :homebase-chat:jvmTest` must stay green (it exercises create/leave/
delete/heal paths via `ConversationServiceTestFixture.kt`). The structural guarantee that nothing
sensitive remains is the residue grep in Step 11 (treat it as the "test" for this change). If you find
the suite has a gap (e.g. heal flow not covered), do NOT add a test in this plan — note it as a deferred
follow-up; adding tests would expand scope beyond logging removal.

Model any future verification after the heal tests already present in
`homebase-chat/src/jvmTest/kotlin/id/homebase/chat/services/convo/` (they use FAKES, not mocks).

## Done criteria

- [ ] `grep -rn "MethodAudit\|ConvoAudit\|ParticipantsAudit\|unsafeBytes.*toBase64\|aesKey.*toBase64" homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/` → no output.
- [ ] `grep -rn "audit: MethodAudit" homebase-chat/src/commonMain/` → no output.
- [ ] `MethodAudit.kt` deleted; `CONVERSATION_SERVICE_DEBUG.md` deleted (`GONE`).
- [ ] `import id.homebase.api.sync.database.QueryBatch` still present in ConversationService.kt AND line ~1737 still uses `QueryBatch(` (real logic untouched).
- [ ] `./gradlew :homebase-chat:compileKotlinJvm :homebase-chat:compileAndroidMain` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-chat:compileKotlinIosSimulatorArm64` → `BUILD SUCCESSFUL` (or noted "not macOS, skipped").
- [ ] `./gradlew :homebase-chat:jvmTest` → `BUILD SUCCESSFUL`, 0 failures.
- [ ] `git status --porcelain` shows ONLY: modified `ConversationService.kt`, `GroupHealService.kt`, `ConversationServiceCollaborators.kt`; deleted `MethodAudit.kt`, `CONVERSATION_SERVICE_DEBUG.md`; plus `plans/` files. No other source file changed.
- [ ] `plans/README.md` row for Plan 002 marked done.

## STOP conditions (specific to this plan)

- **Drift:** the Step 1 baseline diff is non-empty and live code no longer matches a cited excerpt
  (e.g. a fence moved, a method was renamed, the aesKey lines were already removed by someone else).
- **A fence wraps real logic you cannot cleanly separate.** If removing an audit block would require
  deleting a `throw`, `error(...)`, `return`, a real call (`updateConversationInternal`, `tryEnqueue`,
  `writeOrReplace*`, `optimisticWriter.*`), or a value read by later real code — STOP and report the
  exact location rather than guessing. (The known interleavings are documented in Traps; an *unlisted*
  one is a STOP.)
- **The fix needs an out-of-scope file** beyond the 5 listed (e.g. a test fake turns out to implement
  `GroupHealConversationOps` with the audit param, or another module references `MethodAudit`). Grep
  said no, but if compilation demands it, STOP and report.
- **QueryBatch assumption proves false:** if `grep QueryBatch ConversationService.kt` returns ONLY
  audit-removed lines after Step 6 (i.e. line 1737 was itself audit, not real logic), reassess before
  removing the import — but per this plan's read, 1737 is real (`tryHardDeleteUnrecoverableOwnHeader`).
- **Any verification command fails twice** after a corrective edit — STOP and report the error.

## Maintenance notes

- **Follow-up (separate plan, recommended):** the genuine operational `Logger.i/d` lines that survive
  in GroupHealService.kt and elsewhere still log recipient **domain names** at Verbose/Info, which land
  in `homebase.log` and Crashlytics breadcrumbs in release. The structural fix is to raise
  `LoggerConfig` default severity (or override it per entry point to `Severity.Info`/`Warn` in release)
  AND/OR scrub domain names from operational logs. That is intentionally OUT of scope here (touches
  `LoggerConfig.kt` + 3 entry points) — track it as Plan 00X "tighten release log severity & PII scrubbing".
- A reviewer should scrutinize the **four traps** (createConversation revive, deleteConversation
  STEP 1/2, updateConversationTags guard, clearConversation) to confirm the `throw`/`error`/`return`
  semantics are byte-for-byte preserved — those are the only places where a careless block-delete could
  silently change behaviour (e.g. dropping the `error("Conversation not found")` at line 2076).
- Confirm the `homebase-core/build/...ComposeApp.h` hits for `MethodAudit` are generated framework
  artifacts (they are) and were not edited.
- Once merged, the "DO NOT MERGE" debt and the `unsafeBytes.toBase64()` key-leak pattern are gone;
  a `grep -rn "unsafeBytes.*toBase64" homebase-chat/` guard could be added to CI as a future safeguard.
