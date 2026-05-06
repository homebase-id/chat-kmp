# ConversationService Debug-Instrumentation Reference

**Status: temporary debug instrumentation — DO NOT MERGE.**

This document is the companion to a comprehensive audit-trail logger added to
every mutating method on
`homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt`,
plus a **participant-list trace** that follows the recipients/participants list
across the createConversation → write → readback → mapper data path
(`homebase-chat/.../convo/ConversationMapper.kt`).

When the user brings in a `homebase.log` and asks "why didn't this conversation
operation work?", read this doc first, then walk the **Triage Order** at the bottom.

There are **two log tags** to filter on:

- `ConvoAudit` — method-level PRE/STEP/POST/DIAGNOSIS for every mutating method.
- `ParticipantsAudit` — participant-list lifecycle (input → normalize → persist →
  readback → mapper). Use this when investigating "group member missing from
  the list" bugs. See Section 9 below.

---

## 1. The mechanic

A private `MethodAudit` helper class at the top of `ConversationService.kt`
emits log lines under tag `ConvoAudit`. Every audited method:

1. Logs a `═════ <method> START ...` banner with the args.
2. Logs `[<method>] PRE …` lines describing the relevant input/file state.
3. Logs `[<method>] STEP N <action>` for each major operation it performs.
4. Logs `[<method>] BUG? <name>: <explanation>` whenever an assertion fails.
   The `BUG?` prefix is the trigger phrase — `grep "BUG?" homebase.log` jumps
   straight to anomalies.
5. Records each assertion as `<checkName>=PASS|FAIL|WARN|THREW`.
6. Logs `[<method>] POST …` lines describing the resulting state.
7. Closes with a single grep-friendly summary:
   `DIAGNOSIS: <method> verdict=<OK|WARN|FAIL> <check1>=… <check2>=… …`
8. Logs `═════ <method> END ═════`.

Verdict logic: any `FAIL` or `THREW` → `FAIL`. Any `WARN` → `WARN`. Otherwise `OK`.

When a method throws partway through, you still get a final `DIAGNOSIS` line
and an `END` marker, with an explanatory tail like `ABORTED at STEP 2`.

To filter the log:

```bash
grep '(ConvoAudit)' homebase.log              # everything
grep '(ConvoAudit)' homebase.log | grep '═════'    # method boundaries only
grep '(ConvoAudit)' homebase.log | grep DIAGNOSIS  # one-line-per-method summary
grep '(ConvoAudit)' homebase.log | grep 'BUG?'     # anomalies only
```

---

## 2. Methods covered

Tier 1 — full PRE/STEP/POST + named checks (the methods most relevant to the
delete-group-conversation bug we're hunting):

- `createConversation`
- `writeConversationFile` (private; called by createConversation, recover, note-to-self)
- `updateConversationInternal` (private; called by many — the central update path)
- `updateConversationTags` (private; called by leaveGroup, accept/declineRejoin, archive/pin)
- `leaveGroup`
- `deleteConversation`

Tier 2 — START/STEP/POST + named checks but lighter-touch:

- `updateAdmins`
- `updateGroupMembers`
- `updateConversation` (public)
- `acceptRejoin`
- `declineRejoin`
- `recoverConversation`
- `ensureNoteToSelfExists`
- `clearConversation`
- `healGroupDistribution`

Tier 3 — START/finish with try/catch wrap (one-shot tag flips):

- `archiveConversation`
- `unarchiveConversation`
- `pinConversation`
- `unpinConversation`
- `introduceEveryone`

Read-only accessors (`requireConversation`, `requireConversationFileId`,
`getConversation`, `getConversationHomebaseFile`, `getConversationAdminHomebaseFile`,
`getAdmins`) are **not instrumented** — they'd just add noise.

---

## 3. Per-method check inventory

Each row: check name → what FAIL means → most likely cause.

### `createConversation`

| Check | FAIL means |
|---|---|
| `revive` | An existing-but-dead conversation file failed to revive; threw inside `updateConversationInternal`. Look for that method's audit block immediately above. |
| `existingFileBranch` | (always PASS unless the existing-file path threw) sentinel that we returned via the existing-file branch instead of creating fresh. |
| `writeConversationFile` | Either the inner method returned `false` (outbox enqueue failed) or it threw. The conversation does not exist locally or on the server. |
| `uploadAdminFile` | Group conversation file uploaded but admin file enqueue threw. Group exists but admin distribution will fail; recipients will see no admin info. |
| `trySendIntroductions` | (always PASS — the inner method swallows errors) sentinel only. |
| `sendGroupStartedStatus` | The "X added you to a group chat" status message threw. Group exists but no kickoff message — recipients see the conversation appear silently. |
| `postFileExists` | `createConversation` returned but the conversation file is not visible from the local index. Indicates a DB-write/query mismatch in `optimisticWriter.writeNewFile`. |
| `postOutboxDelta` | Outbox grew by fewer rows than expected. For a group, expect ≥3 (conversation + admin + status); for 1:1 expect ≥1. A `-1` from expected means one enqueue silently deduped (UNIQUE collision). |

### `writeConversationFile` (private)

| Check | FAIL means |
|---|---|
| `optimisticWriteNew` | `optimisticWriter.writeNewFile` threw. The DB row never got written; the file will not appear in the local index. |
| `loadConversation` | `conversationStream.loadConversation` threw. Original code did not catch this — pre-existing bug or transient; we log and continue (matches original behaviour). |
| `outboxEnqueue` | `outboxSync.tryEnqueue` returned `false`. The upload request was not enqueued; the file will never reach the server. |

### `updateConversationInternal` (private)

| Check | FAIL means |
|---|---|
| `preFileExists` | No conversation file found locally — `error("No conversation found")` will throw. Caller passed an unknown ID, or the file was deleted between the caller's read and this method's read. |
| `replaceEnqueue` | `outboxSync.replaceEnqueue` returned `false`. The update was not enqueued. NOTE: replaceEnqueue dedupes by uniqueId — `false` here means a same-uniqueId row exists and the caller's intent was rejected. |
| `postArchivalStatusApplied` | (only checked when caller passed a non-null `archivalStatus`) The local file's `archivalStatus` does not reflect what was passed. Indicates `optimisticWriter.writeUpdate` is commented out (lines ~935-939) and there is no other code path applying the change locally. **This is a real candidate for the delete-group bug**: `updateConversationInternal` returns success but the local file's archivalStatus stays at the pre-call value. |

### `updateConversationTags` (private)

| Check | FAIL means |
|---|---|
| `preFileExists` | No conversation file found locally — will throw `error("Conversation not found")`. |
| `optimisticUpdateLocalTags` | `optimisticWriter.updateLocalTags` threw. Local tags were not updated. |
| `step2Enqueue` | The server-side tag-update outbox row did not enqueue. Tags are flipped locally but will not propagate. |
| `postLocalTags` | `optimisticWriter.updateLocalTags` returned without throwing but the file's localTags do not match the new set. Smoking gun for an optimistic-writer bug or a concurrent modification. |

### `leaveGroup`

| Check | FAIL means |
|---|---|
| `isGroupGuard` | Called on a non-group conversation. UI bug — should not have offered Leave. |
| `localOnlyAddLeftTag` / `postLeftTagPresent` | Local-only path (legacy group or `forceLocalOnly=true`) failed to flip the LeftTag. UI will not show Left state. |
| `soleAdminGuard` | Caller is the only admin in a non-isolated group — must promote another before leaving. |
| `step1SendLeaveMessage` | The "X left" status message threw. **WARN, not FAIL** — original code swallows this; leave still proceeds. |
| `step2RemoveSelf` | `updateConversationInternal` to remove self from participants threw. Step 1's optimistic message is rolled back; leave **does not complete** and the conversation stays in its current state. |
| `step3UpdateAdminFile` | (only when caller is an admin) Admin-file update threw. Participants updated but admin set is stale; the next join could hit a conflicted-admin state. |
| `step4AddLeftTag` | `updateConversationTags` to add LeftTag threw. Participants and admin file updated server-side but local LeftTag not flipped — UI will not show Left state, and the user will appear stuck. |
| `postLeftTagPresent` | After all 4 steps returned, the file's localTags do not contain LeftTag. Suggests the optimisticWriter pathway in step 4 is silently failing. |
| `postState` | UI conversation state is not Left or RejoinPending. The mapper isn't reading the LeftTag correctly OR the server-side state changed it back. |
| `postOutboxDelta` | Fewer than 3 new outbox rows. One of the steps' enqueues silently deduped (UNIQUE collision is most common). |

### `deleteConversation`

| Check | FAIL means |
|---|---|
| `preFileExists` | (WARN only) Conversation row exists in the UI/index but the underlying file is missing. STEP 2 will throw. |
| `guard` | Group is not in `[Left, RejoinPending, Removed, Archived]`. The UI offered Delete in error. Or: leaveGroup didn't actually flip state. |
| `step1Enqueue` | `DeleteFilesByGroupIdOutboxRequest` enqueue returned false. Server-side delete will not happen. **Most common cause** when a previous incomplete delete left a stale outbox row. |
| `step2Update` | `updateConversationInternal(archivalStatus=Removed, distribute=false)` threw. Server-side delete is queued but local file is NOT marked Removed. UI will continue to show the conversation. |
| `postArchivalStatus` | `step2Update` returned but file's archivalStatus is not Removed. **Same root cause as `postArchivalStatusApplied` in `updateConversationInternal`.** |
| `postVersionTagChanged` | versionTag did not change between pre/post. Update was a no-op or never reached the file index. |
| `postUiState` | UI state is not Removed/Deleted/null. Delete will appear as a no-op to the user. |
| `postOutboxDelta` | Fewer than 2 new outbox rows. STEP 1 or STEP 2's enqueue silently deduped. |

### `updateAdmins` / `updateGroupMembers` / `updateConversation` (public)

| Check | FAIL means |
|---|---|
| `legacyGroupGuard` | Called on a legacy group — admin/member management not supported. |
| `updateAdminFile` | (updateAdmins) Admin-file update threw. |
| `updateConversationInternal` | (updateConversation) The inner update threw. |

### `acceptRejoin` / `declineRejoin`

| Check | FAIL means |
|---|---|
| `rejoinPendingGuard` | Conversation is not in RejoinPending state — caller-side bug. |
| `removeLeftTag` (acceptRejoin) | The LeftTag removal failed — UI will continue to show RejoinPending. |
| `step1SendDeclineMessage` | (declineRejoin) Status message threw. |
| `step2RemoveSelf` | (declineRejoin) `updateConversationInternal` threw — decline did not complete. |
| `step3AddLeftTag` | (declineRejoin) LeftTag flip failed — local state inconsistent with server. |

### `ensureNoteToSelfExists`

| Check | FAIL means |
|---|---|
| `writeConversationFile` | First-ever creation failed; note-to-self does not exist. |

### `clearConversation`

| Check | FAIL means |
|---|---|
| `enqueue` | `tryEnqueue` returned false (UNIQUE collision). Clear NOT performed. |

### `healGroupDistribution`

| Check | FAIL means |
|---|---|
| `isGroupGuard` | Called on a non-group conversation. |
| `anythingHealed` | Neither main file nor admin file was redistributed. Caller is not the original author of either; nothing to do. |

### `recoverConversation`

| Check | FAIL means |
|---|---|
| `revive` | The reviving `updateConversationInternal` call threw. |
| `writeConversationFile` | The fresh-creation `writeConversationFile` returned false or threw. |

### Tier 3 (archive/unarchive/pin/unpin/introduceEveryone)

These wrap the call in try/catch; FAIL means the inner call (typically
`updateConversationTags` or `trySendIntroductions`) threw.

---

## 4. Triage order when a log comes back

1. **`grep '(ConvoAudit)' homebase.log | grep DIAGNOSIS`**.
   Each `DIAGNOSIS:` line is one method run. Find the one for the operation
   the user attempted (`deleteConversation`, `leaveGroup`, etc.). The
   `verdict=` and the `<check>=FAIL` entries are the answer.

2. **For each FAIL check**, look up the row in Section 3 above. Then
   `grep -B 5 -A 30 '═════ <method> START'` around the relevant method
   to read the full audit block.

3. **Look for `BUG?` lines** — every check failure emits one with the
   most likely cause inline. They're greppable in isolation:
   `grep 'BUG?' homebase.log`.

4. **Cross-reference nested calls.** Many methods call into others
   (e.g. `deleteConversation` → `updateConversationInternal` →
   `optimisticWriter`). The audit logs nest in call order, so the inner
   method's `END` line appears *before* the outer one's. If the outer
   method shows `step2Update=THREW`, scroll up to find the inner method's
   `DIAGNOSIS` for the underlying cause.

5. **State-machine transitions across methods.** A typical bug-hunt path
   for "delete group didn't work":
   - Look for the most recent `leaveGroup` `DIAGNOSIS` first — its verdict
     tells you whether the group ever reached `Left` state.
   - Then look for the `deleteConversation` `DIAGNOSIS` — if `guard=FAIL`,
     it means the leave didn't actually take, despite leaveGroup reporting OK.

6. **Outbox health.** Multiple methods include `postOutboxDelta=FAIL` when
   their enqueues silently dedupe. If you see this across multiple methods
   in a row, the outbox is wedged behind a stuck row — search the log
   for `OutboxSync` failures around the same time.

---

## 5. Common bug shapes and what they look like in the log

### Bug shape A: silent dedup on retry

A previous incomplete operation left a same-uniqueId row in the outbox.
Subsequent `tryEnqueue` calls on the same uniqueId return `false`.

```
[deleteConversation] BUG? step1Enqueue: tryEnqueue returned false ...
DIAGNOSIS: deleteConversation verdict=FAIL preFileExists=PASS guard=PASS step1Enqueue=FAIL
```

Fix direction: clear the stuck outbox row, or change the affected method
to use `replaceEnqueue` (which supersedes) rather than `tryEnqueue`.

### Bug shape B: optimistic write didn't apply

`updateConversationInternal` returns success but the local file's
`archivalStatus` stays at the pre-call value.

```
[deleteConversation] BUG? postArchivalStatus: file.archivalStatus is null (expected Removed) ...
DIAGNOSIS: deleteConversation verdict=FAIL ... step2Update=PASS postArchivalStatus=FAIL ...
```

Fix direction: re-enable the commented-out `optimisticWriter.writeUpdate(...)`
call in `updateConversationInternal` (lines ~935-939), or add an alternate
path that flips archivalStatus locally.

### Bug shape C: leaveGroup partially completed but UI stuck

```
[leaveGroup] BUG? step4AddLeftTag: ... threw: ...
[leaveGroup] BUG? postLeftTagPresent: LeftTag NOT in localTags ...
DIAGNOSIS: leaveGroup verdict=FAIL ... step4AddLeftTag=THREW postLeftTagPresent=FAIL ...
```

Then later:

```
[deleteConversation] BUG? GUARD REJECT: ...
DIAGNOSIS: deleteConversation verdict=FAIL guard=FAIL ...
```

Fix direction: the leave step 4 failure is the root cause; deleteConversation
correctly refused to delete a non-Left conversation.

### Bug shape D: cross-identity transit blocking the outbox

Methods that distribute to recipients (createConversation, updateGroupMembers,
leaveGroup step 2, etc.) enqueue uploads. If a recipient is unreachable,
the upload retries forever, blocking subsequent same-driveId rows.

In the log you'll see repeated `OutboxSync` retry warnings on the same
fileId, and our `postOutboxDelta` checks pass (rows enqueue fine) but
**later** operations fail because the outbox isn't draining. Cross-reference
with the `IOS_HLS_DEBUG.md` and the analysis we did for the unreachable
contact `tt.jankins.demo.rocks`.

---

## 6. To revert when done

1. **Delete the `MethodAudit` class** (top of `ConversationService.kt`,
   inside the banner box).
2. **Search for `// ---- DEBUG instrumentation`** and `// ---- end DEBUG`
   in the file — every instrumented region is fenced. Remove each block.
3. **Restore the original method bodies.** Most are intact; the audit
   wrappers were added around them. The ones to double-check (because
   I rewrote control flow):
   - `deleteConversation` — original body is comment-preserved? No, it
     was replaced inline; the original logic remains identical, just
     wrapped in audit calls. Diff before reverting.
   - `leaveGroup` step 1: original used a bare `try { ... } catch (t: Throwable) { Logger.e(...) }`.
     My version preserves that but adds `audit.checkPass`/`checkWarn` inside
     the catch. Restore original by keeping the `try`/`catch` and removing
     the audit calls.
   - `leaveGroup` step 2: original used a `try { ... } catch (t: Throwable) { rollback; throw }`.
     My version uses the same control flow with audit calls inside. Restore
     similarly.
4. **Remove the `import id.homebase.api.sync.database.QueryBatch` line** if
   it's no longer needed (used only by the audit blocks in deleteConversation
   and createConversation pre/post counts).

When in doubt, `git diff homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt`
and remove all the additions tagged with `// ---- DEBUG instrumentation` /
`// ---- end DEBUG` plus the `MethodAudit` class.

---

## 7. Files touched

- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/services/convo/ConversationService.kt`
  — `MethodAudit` helper class added at top; every mutating method
  instrumented with audit calls.
- `import id.homebase.api.sync.database.QueryBatch` — added near top of imports.
- This file (`CONVERSATION_SERVICE_DEBUG.md`).

No other source files are modified.

The audit instrumentation does NOT modify behaviour — every call site preserves
the original control flow, return value, and exception-throwing semantics.
The audit only adds log entries and reads from the same data the original code
already touched.

---

## 8. When the user comes back with a log

1. Read this document first.
2. Run the triage commands in Section 4 against the new log.
3. For each `verdict=FAIL` line, find the matching method in Section 3 and
   the matching `BUG?` lines in the log to identify the root cause.
4. Cross-reference Section 5 for common bug shapes.

The single most diagnostic line per operation is the
`DIAGNOSIS: <method> verdict=… <check>=…` summary. Read that first; only
dive into the verbose logs when something is `FAIL` or `THREW`.

---

## 9. Participant-list trace (`ParticipantsAudit` tag)

A separate set of log entries follows the participant/recipient list across
the full create-and-display data path. Use this when investigating bugs of
the shape **"created a group, but the member list is missing some recipients."**

Filter:

```bash
grep '(ParticipantsAudit)' homebase.log
```

### The expected sequence for a healthy group create

For one `createConversation` call you should see, in order, six lines (rough
shape):

```
createConversation INPUT: rawRecipients.size=N domains=[a,b,c,...] rawHasNull=false duplicateCount=0
createConversation NORMALIZED: domain=<self> normalized=[a,b,c,...] droppedAsSelf=0 droppedAsDup=[]
createConversation ALL_PARTICIPANTS (will be persisted ...): size=N+1 domains=[a,b,c,...,<self>] (includes self=true)
writeConversationFile SERIALIZED content for <id>: {"title":"...","version":1,"recipients":["a","b","c",...,"<self>"]}
writeConversationFile READBACK after optimisticWriter for <id>: fileExists=true recipients.size=N+1 domains=[a,b,c,...,<self>] title='...'
ConversationMapper.mapToBasic READ for <id>: rawRecipients.size=N+1 nullCount=0 distinctDropped=0 final.size=N+1 domains=[a,b,c,...,<self>] isGroup=true ...
```

Each line is the next stage in the pipeline. The `domains=[…]` segment is the
authoritative source of truth at each layer.

### Where the bug is, by which line first shows the wrong size

| First wrong line | Layer at fault | Likely cause |
|---|---|---|
| `INPUT` already shorter than what you selected | UI selection | Selection screen passing an incomplete list to `createConversation`. Investigate the call site (e.g. `ConversationListViewModel.startConversation` / new-chat picker). |
| `INPUT` size correct, `NORMALIZED` shorter than expected and `droppedAsDup` non-empty | UI selection (duplicates) | Selection layer let duplicates through; `distinct()` collapses them. |
| `INPUT` size correct, `NORMALIZED` shorter and `droppedAsSelf` > 0 | UI selection (self-add) | Selection layer included self; `filterNot { it == domain }` removes it. |
| `ALL_PARTICIPANTS` shorter than `NORMALIZED + 1` | createConversation math | Bug in `(normalizedRecipients + domain).distinct()` (rare; would mean caller passed self as one of the recipients in a way that survived prior steps). |
| `SERIALIZED` content's `recipients` JSON array shorter than `ALL_PARTICIPANTS` | `ConversationAppDataJson` construction or its custom serializer | Inspect `ConversationAppDataJsonSerializer.serialize` at lines 65-84. |
| `READBACK` shorter than what `SERIALIZED` showed | optimisticWriter or DB persistence | The optimistic write dropped entries, or the file index normalises content somehow. |
| `READBACK` looks fine, but `mapToBasic` is shorter | mapper or deserializer | Inspect `ConversationAppDataJsonSerializer.deserialize` or `mapToBasic`'s `filterNotNull().distinct()`. The two warnings `nullCount=…` and `distinctDropped=…` pinpoint which one. |
| `READBACK` looks fine, `mapToBasic` looks fine, but UI is shorter | UI layer (out of scope of this instrumentation) | Group-settings ViewModel, contact resolver dropping unknowns, or filter on the display side. |

### Detecting overwrites by later updates

`updateConversationInternal` is the central rewrite path. After
`createConversation`, almost any other group operation re-enters
`updateConversationInternal` (group-member updates, recovery, revive, leave,
delete). Each call logs:

```
updateConversationInternal WRITE for <id>: newParticipants.size=… domains=[…]
updateConversationInternal DIFF for <id>: prior.size=… priorDomains=[…] new.size=… dropped=[…] added=[…]
updateConversationInternal POST_READBACK for <id>: file.recipients.size=… domains=[…] (intended new size=…)
```

What to look for:

- `dropped=[…]` non-empty when caller is NOT `updateGroupMembers` or `leaveGroup` →
  likely a revive/recovery path overwriting with a stale list. Check the call
  stack: who called `updateConversationInternal`?
- `POST_READBACK MISMATCH` warning → the optimistic write didn't apply locally
  yet. The participant change is in the outbox but the file index still shows
  the old list. **`updateConversationInternal` has a commented-out
  `optimisticWriter.writeUpdate(...)` at lines ~935-939 in the original source
  (look for `// optimisticWriter.writeUpdate`).** This is a strong candidate
  for the "created a group, members missing right after" bug — the local
  view simply doesn't reflect the new participants until a server round-trip.

### Common bug shapes for participants

**Shape A — caller passes self as a recipient**
```
INPUT: rawRecipients.size=4 ...
NORMALIZED: domain=<self> normalized=[a,b,c] droppedAsSelf=1 droppedAsDup=[]
```
`droppedAsSelf > 0` means the UI/selection layer included self. Not a bug per
se (the normalizer handles it) but indicates the caller is messy.

**Shape B — duplicate recipients from selection**
```
INPUT: rawRecipients.size=4 ... duplicateCount=1
NORMALIZED: ... droppedAsDup=[a]
```
The selection layer let a dup through. Group still creates correctly.

**Shape C — corrupt stored content**
```
ConversationMapper.mapToBasic READ for <id>: rawRecipients.size=5 nullCount=2 distinctDropped=0 final.size=3 ...
WARN: 2 null entries in recipients — deserializer produced nulls (corrupt content or schema drift)
```
The stored conversation file has nulls in its recipients array. Investigate
the writer; one of the historical updates wrote bad data. Check the `WRITE`
lines from prior `updateConversationInternal` calls for this conversationId.

**Shape D — optimistic-write miss after createConversation**
```
ALL_PARTICIPANTS ... size=4 domains=[a,b,c,<self>]
SERIALIZED ... "recipients":["a","b","c","<self>"]
READBACK ... recipients.size=4 domains=[a,b,c,<self>]
ConversationMapper.mapToBasic READ ... final.size=4 domains=[a,b,c,<self>]
```
Looks fine in the data layer. If the UI is still missing members, the bug is
in the UI (group-settings ViewModel, contact-resolution, etc.) — not in
ConversationService. Pivot the investigation.

**Shape E — revive shrunk the participant list**
```
updateConversationInternal SHRINKING participants for <id>: removing [d,e] ...
```
Some path called `updateConversationInternal` with fewer participants than
the file currently has. If the call stack isn't `updateGroupMembers` or
`leaveGroup`, that's the bug. The most likely culprits: `recoverConversation`
or `createConversation`'s revive branch using stale data.

### How to use this against a captured log

1. `grep '(ParticipantsAudit)' homebase.log > pa.log` to isolate.
2. Find the `createConversation INPUT:` line for the failing run.
3. Walk the 6-line sequence (Section 9, "expected sequence") and find the
   first line where the size shrinks.
4. Use the table to map line → layer → root cause.
5. If all six lines look healthy but the UI still shows fewer members, the
   bug is on the UI side; pivot the investigation away from
   ConversationService.
