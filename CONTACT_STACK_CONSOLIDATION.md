# Contact Stack Consolidation — Migration Plan

Status: **proposal / not started**. Author: investigation on branch
`connection-circle-state-notifications`.

## 1. Problem

We have **two complete, parallel contact stacks** operating on the **same** Contacts
drive. They evolved separately (the chat connection system first, the Contact Book
add-on second) and now overlap.

| | Chat stack (older) | Contact-book stack (newer) |
|---|---|---|
| **Read / parse** | `DriveContactService.mapToContact` → `ContactServerFile` / `chat.ContactName` → `ContactUiModel` | `ContactBookStream` → `toContactBookEntry()` → `ContactContent` / `api.ContactName` → `ContactBookEntry` |
| **Write** | `DriveContactService.saveContact` / `saveContactForOdinId` → **direct drive upload** (`DriveUploadProvider`, full overwrite) | `ContactBookService` → `ContactsProvider` → **V2 controller** `/api/v2/contacts` (server-side field merge) |
| **Write callers** | `ConnectionRequestService` (connection accept/finalize) | Contact Book create / edit / import |
| **Consumers** | chat people-pickers: new conversation, add/select members, conversation list, conversation/group settings | Contact Book manager UI (list + detail) |

They **do interoperate** — both use `contactTargetDrive`, `fileType = 100`
(`ChatProtocol.ContactFileType` == `ContactsProvider.CONTACT_FILE_TYPE`), and image
payload key `"prfl_pic"` (`ContactProtocol.ProfileImageKey` ==
`ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY`). So a contact written by either is
visible to both. That overlap is exactly why the duplication is dangerous rather than
merely wasteful.

### Concrete defects this causes

1. **Two write mechanics on one file.** Direct upload **overwrites** the file it
   builds; the V2 controller does **field-level merge**. A contact auto-created on
   connection-accept (direct upload) and later edited in the Contact Book (V2 merge),
   or vice-versa, can clobber fields or fight over `versionTag`.
2. **Two parse models drift.** It's not just `ContactName` — the chat package
   (`id.homebase.chat.services.convo.contact`) carries a **full parallel set** of
   sub-models (`ContactName`, `ContactPhone`, `ContactEmail`, `ContactLocation`,
   `ContactBirthday`, `ContactImage`, wrapped in `ContactServerFile`), independent
   copies of the `homebase-api` `ContactContent` family. The user-visible "contact
   details show None" bug is a symptom: the name lives in `displayName` while the
   detail/edit derive `givenName`/`surname` inconsistently.
3. **Body-spill divergence.** When a contact is too large to embed in the header, the
   chat path spills the body to a `"dflt_key"` payload. `toContactBookEntry()` only
   reads header content (`fileMetadata.appData.content`) → such a contact renders as
   `null`/missing in the Contact Book.
4. **Duplicated image/thumbnail/AES logic** in `DriveContactService.saveContact` and
   `ContactsProvider.setContactImage`.
5. **Name redundancy (schema-level).** `ContactName` stores both `displayName` and
   `givenName`/`additionalName`/`surname`; the two can disagree and are handled
   inconsistently across read/edit/write.
6. **No-clear merge semantics (pre-existing, contact-book write).** The V2 controller
   merges per-leaf with `Coalesce(incoming, existing)` and treats absent/empty as "leave
   alone" — so **a field can never be blanked via UPDATE/sync**. The Contact Book edit
   form already writes through V2, so clearing a phone/email/etc and saving silently
   keeps the old value. This is independent of the migration but should be tracked/fixed
   (needs a server-supported clear, e.g. an explicit sentinel or a dedicated clear op).

### Phase 1 timing — RESOLVED by backend answers

Earlier concern: `syncContact` looked async (202) vs the old immediate client write.
**Backend confirms the 202 is fully synchronous server-side** — `EnsureExistsAsync` +
`EnrichAsync` are awaited before the 202 returns, the write uses `raiseEvent: true`, so
the contact file is **already written and queryable and the WS push already raised** by
the time the client gets the 202. So there is effectively no immediate-vs-async gap.
Worst case (peer offline / no profile) leaves a **stub** contact (`odinId` only), still
queryable — name falls back to the domain in the UI. This de-risks Phase 1 substantially.

## 2. Target architecture

- **One write path:** the V2 `ContactsProvider` (server merge) for all contact writes,
  including connection-driven ones.
- **One canonical read model + parser** in `homebase-api` (e.g. `ContactRecord` parsed
  from `HomebaseFile`, handling both header-embedded and spilled-payload bodies).
- **One `ContactName`.**
- The two UI models stay as **thin views** over the canonical model:
  `ContactUiModel` (lightweight, connection-oriented, for chat pickers) and
  `ContactBookEntry` (rich, for the manager). Same source data, derived shapes.

## 3. Phases

### Phase 0 — Safety net (no behavior change)  *(DONE — tests added)*
- ✅ JVM tests pinning current parse behavior:
  - `homebase-core` → `ContactBookEntryParseTest` pins `HomebaseFile.toContactBookEntry()`
    (full contact, the synced/displayName-only "None" case, name-derivation fallbacks,
    image-payload detection, and the null/spilled-body/invalid-JSON returns-null cases).
  - `homebase-chat` → `ContactModelParityTest` pins that `ContactContent` (api) and
    `ContactServerFile` (chat) are wire-compatible both directions, so any future model
    drift fails a test instead of leaking to production.
- ✅ **Answered by backend** (see §6) — `syncContact` enrichment, timing, image,
  source, keying, merge/spill semantics all confirmed from server code. No blockers
  remain for Phase 1.

### Phase 1 — Converge writes onto V2  *(DONE — commit 73a9b695)*
- Replace the four `driveContactService.saveContactForOdinId(...)` calls in
  `ConnectionRequestService` with `contactsProvider.syncContact(...)`.
- **Timing risk: resolved** — the 202 is synchronous server-side (see §1.5). No gap.
- **Behavior deltas to expect (all acceptable, mostly upgrades):**
  - These calls fire on connection accept/finalize, i.e. when the peer **is connected**,
    so the server takes the *peer-profile* path → the new contact gets the **full**
    `ContactName` + location/phone/email/birthday, vs the old path's public-profile
    `displayName`+`givenName`+`surname` only. Strictly more data.
  - **`source` is no longer set** by sync (old path set `source="public"`). Audit any
    client logic keyed on `source` (`ContactBookSource.CONNECTION = "public"`). Likely
    cosmetic — the Introduced/Confirmed pills use connection state, not `source` — but
    confirm before deleting the old path.
  - Neither path sets the avatar at accept time (image is a separate endpoint), so no
    regression there.
- Once no callers remain, delete `DriveContactService.saveContact` /
  `saveContactForOdinId` and any now-unused image-write/`ContactSizer` code there.
- **Outcome:** single writer, single merge semantics. Defect #1 gone.

### Phase 2 — Unify the model
- Move/keep one `ContactName` in `homebase-api`; delete `chat.ContactName`.
- Introduce one canonical parsed model + a single parser over `HomebaseFile` that
  merges the logic of `toContactBookEntry()` and `DriveContactService.mapToContact`.
  - Note (per backend §2): the **V2 controller never spills** — contact JSON is always
    in the header (max 10,240 bytes ciphertext; over-budget fails, no payload fallback).
    Its only payloads are `prfl_pic` (image) and `merge_log` (history). So the spilled
    `"dflt_key"` body is a **legacy artifact of the old chat write path only**. The
    unified parser should still tolerate it for contacts written before Phase 1, but
    nothing new will produce it. (fixes defect #3)
- Normalize name handling **in one place**: on read, derive first/last from
  `displayName` when parts are blank; on write, keep `displayName` and parts consistent
  (fixes defect #2 / #5).

### Phase 3 — Unify the read service
- One shared contact read service/stream (in `homebase-api`, consumed by both chat and
  core). Retire `DriveContactService.mapToContact`; chat pickers consume the shared
  stream and derive `ContactUiModel`. The Contact Book derives `ContactBookEntry`.

### Phase 4 — Cleanup
- Remove duplicated image/thumbnail/AES logic; single image read/write via
  `ContactsProvider` (fixes defect #4).
- Remove `ContactServerFile` if fully replaced.

## 4. Risks

| Risk | Phase | Mitigation |
|---|---|---|
| ~~Connection-accept contact now async~~ | 1 | **Resolved** — 202 is synchronous + raises WS push (§1.5) |
| ~~`syncContact` enrichment parity~~ | 1 | **Resolved** — connected path writes full name+fields; ≥ old path (§1.1) |
| `source="public"` no longer set on connection contacts | 1 | audit client uses of `source`; cosmetic if none |
| Legacy `"dflt_key"`-spilled contacts (old write path) unreadable | 2 | tolerate the spilled payload in the new parser; V2 never produces it |
| No-clear merge: clearing a field in edit doesn't persist | (pre-existing) | needs server clear support; track separately |
| Module boundaries (model in api, consumers in chat + core) | 2/3 | canonical model + `ContactName` live in `homebase-api` (both depend on it) |
| Konsist/`ArchitectureTest` regressions | all | run `homebase-common:jvmTest` each phase |

## 5. Verification per phase
- `./gradlew :homebase-api:compileKotlinJvm :homebase-chat:compileKotlinJvm :homebase-core:compileKotlinJvm`
- `./gradlew homebase-chat:jvmTest homebase-common:jvmTest homebase-api:jvmTest --rerun-tasks`
- On-device smoke: accept a connection → contact appears with name + avatar; Contact
  Book create/edit/import; contact image set/clear; people-picker name resolution.

## 6. Backend answers (authoritative, from server code)

`POST /api/v2/contacts/sync/{odinId}` → `V2ContactsController.Sync` →
`ContactService.EnsureExistsAsync` + `ContactEnrichmentService.EnrichAsync` →
`ContactService.MergeAsync`.

1. **Enrichment fields — depends on live connection status:**
   - **Connected** (peer-queries their ProfileDrive): full `ContactName`
     (displayName/givenName/additionalName/surname) **plus** location/phone/email/birthday.
   - **Not connected / 403** (anonymous `GET /pub/profile`): **only `name.displayName`**.
2. **Image:** sync never sets it — text only. Avatar is only ever set via
   `PUT /{uniqueId}/image`. (Old client path also didn't set an image on accept → parity.)
3. **`source`:** sync never sets it. A contact first created by sync has no `source`;
   pre-existing `source` is preserved by the merge.
4. **Timing:** 202 Accepted, but **fully synchronous** server-side — contact is written
   and the WS push raised before the 202 returns. Best-effort: peer offline → stub
   (`odinId` only), still queryable.
5. **Existing contact:** merges, never overwrites (overwritten values appended to a
   `merge_log` payload).
6. **Storage:** contact JSON **always in the header**, never spilled to a payload. Max
   `Content` = 10,240 bytes (base64 ciphertext); over-budget **fails** (no spill). Only
   payloads ever present: `prfl_pic`, `merge_log`.
7. **Keying:** `uniqueId = md5(odinId)` on CREATE (random GUID if no odinId); UPDATE never
   re-keys; sync uses the same `md5(odinId)` → introduced/pending/connected all resolve to
   one file.
8. **Merge:** per-leaf `Coalesce(incoming, existing)` — non-empty incoming wins, else keep
   existing. Absent/empty never clears. Applied per sub-field (can set `name.surname`
   without disturbing `name.givenName`). **Consequence: a field cannot be blanked via
   UPDATE/sync** (defect #6).

Remaining (non-blocking): confirm there are no non-chat callers of the
`DriveContactService` write path before deleting it (current grep: only
`ConnectionRequestService`).

## 7. Not part of this migration
- The contact-detail "shows None" UX bug can and should be fixed independently and
  first (surface the name / Homebase ID in the detail; normalize `toDraft`), since it
  doesn't require the full consolidation.
