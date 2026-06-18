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

### Timing nuance that constrains Phase 1

- `DriveContactService.saveContactForOdinId(odinId)`: resolves the peer's public
  profile **client-side** (`publicIdentityRepository.resolve` → `sitedata.json`) and
  writes the contact **immediately**.
- `ContactsProvider.syncContact(odinId)`: the V2 equivalent, but enrichment happens
  **server-side, best-effort, 202 Accepted** — the contact lands later via drive sync.

So the V2 path is the right target, but a straight swap changes connection-accept from
"contact exists immediately" to "contact appears shortly after." See Phase 1 risk.

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
- ⏳ **Still open (needs runtime/backend, can't unit-test):** verify `syncContact`
  populates `displayName` + `givenName`/`surname` + image equivalently to the old
  client-side `saveContactForOdinId`, and confirm the V2 controller's body-spill
  behavior. Track these as the Phase 1 / Phase 2 open questions below.

### Phase 1 — Converge writes onto V2  *(highest value, smallest blast radius, independently shippable)*
- Replace the four `driveContactService.saveContactForOdinId(...)` calls in
  `ConnectionRequestService` with `contactsProvider.syncContact(...)`.
- **Risk:** the immediate-vs-async timing change. Mitigations:
  - The chat read path already falls back to `domainName` when no contact exists
    (`ConversationEnricher`), so a brief gap is cosmetic, not broken.
  - If the gap is unacceptable, have `ContactService`/`ConnectionService` optimistically
    cache the display name from the already-loaded connection registration until the
    synced contact lands.
- Once no callers remain, delete `DriveContactService.saveContact` /
  `saveContactForOdinId` and any now-unused image-write/`ContactSizer` code there.
- **Outcome:** single writer, single merge semantics. Defect #1 gone.

### Phase 2 — Unify the model
- Move/keep one `ContactName` in `homebase-api`; delete `chat.ContactName`.
- Introduce one canonical parsed model + a single parser over `HomebaseFile` that
  merges the logic of `toContactBookEntry()` and `DriveContactService.mapToContact`,
  and **handles the spilled `"dflt_key"` payload** (fixes defect #3).
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
| Connection-accept contact now async (202) instead of immediate | 1 | domainName fallback already exists; optional optimistic name cache |
| `syncContact` doesn't enrich the same fields/image as the client path | 0/1 | verify in Phase 0 before swapping |
| Spilled-payload contacts unreadable by unified reader | 2 | explicitly handle `"dflt_key"` in the new parser |
| Module boundaries (model in api, consumers in chat + core) | 2/3 | canonical model + `ContactName` live in `homebase-api` (both depend on it) |
| Konsist/`ArchitectureTest` regressions | all | run `homebase-common:jvmTest` each phase |

## 5. Verification per phase
- `./gradlew :homebase-api:compileKotlinJvm :homebase-chat:compileKotlinJvm :homebase-core:compileKotlinJvm`
- `./gradlew homebase-chat:jvmTest homebase-common:jvmTest homebase-api:jvmTest --rerun-tasks`
- On-device smoke: accept a connection → contact appears with name + avatar; Contact
  Book create/edit/import; contact image set/clear; people-picker name resolution.

## 6. Open questions (answer before/within the phase noted)
- **(Phase 0/1)** Field + image + timing parity of `syncContact` vs `saveContactForOdinId`.
- **(Phase 0/2)** Does the V2 controller ever spill the body to a payload, and under what size?
- **(Phase 1)** Any non-chat callers of the `DriveContactService` write path? (Current
  grep: only `ConnectionRequestService`.)

## 7. Not part of this migration
- The contact-detail "shows None" UX bug can and should be fixed independently and
  first (surface the name / Homebase ID in the detail; normalize `toDraft`), since it
  doesn't require the full consolidation.
