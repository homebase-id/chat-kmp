# Moments services — handoff

Branch: `moments-framing`

## What this is

Two services in the moments app:

1. **`MomentsPostSenderService`** — lets a caller post or edit a moment
   (description + N image/video attachments) by reusing the chat module's
   attachment-build and payload-encryption pipeline, then enqueueing an
   `UploadFileRequest` / `UpdateFileByUniqueIdRequest` to the existing
   `OutboxSync`. The low-level transport (`DriveOutboxUploader` →
   `DriveUploadProvider`) is unmodified.

2. **`MomentsRecipientLookupService`** — aggregates available recipients
   (chat conversations + contacts) into a single MRU-ordered list of
   moments-domain `MomentsRecipient`s for the composer's recipient picker.
   Source-agnostic public type so future selection sources can land without
   touching the picker.

## Files added

```
homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/
├── MomentsProtocol.kt                  # MomentsAppId, MomentPostFileType=7050, version
├── MomentPostContent.kt                # @Serializable { version, description }
├── MomentsPostSenderService.kt         # postMoment / updateMoment
├── MomentsRecipient.kt                 # sealed type + Individual/Group + opaque id
├── MomentsRecipientMruStore.kt         # persisted bounded MRU list
└── MomentsRecipientLookupService.kt    # combine(contacts, conversations, mru) → recipients
```

## File touched

`homebase-core/src/commonMain/kotlin/id/homebase/core/di/AppModule.kt`:

- Imports for `MomentsPostSenderService`, `MomentsRecipientMruStore`,
  `MomentsRecipientLookupService`.
- Three `singleOf(...)` registrations right after the `MomentsPreferences` line.
- `MomentsRecipientLookupService.start()` invocation inside the
  `onPostAuthenticated` block, alongside `ContactService.start()` /
  `ConversationStream.start()` (the lookup service depends on both being
  started, so it slots in as a third).

## Architectural decisions baked in (from the design conversation)

| Decision | Choice |
|---|---|
| Module location | `homebase-core/moments/services` (no new gradle module) |
| File shape per moment | One drive file per moment with N payloads — description rides in `appData.content`, media as payloads keyed `mmt_0000`, `mmt_0001`, … (server payload-key regex `^[a-z0-9_]{8,10}$`) |
| Distribution | `recipients: List<OdinId>` — empty = local-only (no transit, no push); non-empty = transit + push |
| Reuse | Calls `MessageAttachmentBuilder` and `PayloadBundleEncryptor` from `homebase-chat` directly (no extraction) |
| `MomentPostFileType` | `7050` (placeholder — picked to not collide with existing chat fileTypes 100/7878/8888/8890; flag if there is a registry I missed) |
| `MomentsAppId` | Newly minted UUID `b4d9e7c3-2f1a-4e8b-9c5d-7a3f2e1b4c8d` — **needs backend registration before push notifications will route** |

## Public API

```kotlin
suspend fun postMoment(
    attachments: List<AttachmentInput>,   // reused from id.homebase.chat.services.builder
    description: String,
    recipients: List<OdinId>,             // empty = local-only
    momentUniqueId: Uuid = Uuid.random(),
    userDate: UnixTimeUtc? = null,
): PostMomentResult                       // { uniqueId: Uuid }

suspend fun updateMoment(
    momentUniqueId: Uuid,
    versionTag: Uuid,                     // CAS check against the on-disk file
    description: String,                  // description-only edit (media preserved)
    recipients: List<OdinId>,
): UpdateMomentResult                     // { uniqueId: Uuid }
```

`postMoment` internally:

1. `MessageAttachmentBuilder.build(...)` produces a `PayloadBundle` (handles
   thumbnail generation, video/audio/image branching).
2. `KeyHeader.newRandom16()` for AES key.
3. `PayloadBundleEncryptor.encryptBundle(...)` — handles per-payload IVs and
   video compression via `VideoPayloadProcessor`.
4. `UploadFileMetadata` with `fileType = MomentPostFileType`,
   `uniqueId = momentUniqueId`, `content = serialized MomentPostContent`,
   `previewThumbnail` = smallest preview thumb.
5. `UploadFileRequest` against `momentsLabeledDrive.drive.alias` with
   `TransitOptions(recipients, sendContents = All, useAppNotification = !isLocalOnly)`.
6. `outboxSync.tryEnqueue(request, priority = 1, dependencyUniqueId = null)`.

`updateMoment` internally (mirrors `ChatMessageSenderService.updateMessage`):

1. `DriveFileProvider.getFileHeaderByUid(...)` to read the current file header
   (network call — see "Open items").
2. CAS check: `existing.fileMetadata.versionTag == versionTag`, else error.
3. New `KeyHeader` with fresh random IV but **reuses the original moment's
   AES key** so existing media payloads stay decryptable.
4. `UploadFileMetadata.versionTag = versionTag` set on the request, so the
   server applies the update against the same generation we read.
5. `UpdateManifest.build(payloads = null, toDeletePayloads = null, ...)` —
   empty manifest. Media payloads on the file are left intact (description-
   only edit).
6. `UpdateFileByUniqueIdRequest` with `FileUpdateInstructionSet(locale = Local,
   recipients = recipients, useAppNotification = false)`.
7. `outboxSync.replaceEnqueue(...)` — supersedes any still-pending earlier
   post or edit for the same `(driveId, uniqueId)` rather than racing.

## What is NOT included (deliberate v1 cuts vs. ChatMessageSenderService)

- **No optimistic write** — moment will only appear after the outbox drains and
  sync replays it back. Add an `OptimisticWriter.writeNewFile(...)` call after
  the enqueue if you want immediate UI feedback.
- **No outbox chaining** — `dependencyUniqueId` is hardcoded `null`. Each
  moment is independent. If we want strict send order while offline, add a
  per-user (not per-conversation) chain like
  `ChatMessageSenderService.lastEnqueuedPerConversation`.
- **No reply / no status-message / no forwarding.**
- **`updateMoment` is description-only.** No way to add/remove/replace media
  on an existing moment; media changes require deleting and re-posting. To
  add media editing later, build a `PayloadBundle` from the new attachments
  and pass payloads + a `toDeletePayloads` list (derived from
  `existing.fileMetadata.payloads`) into `UpdateManifest.build(...)`.
- **`updateMoment` makes a network call to read the file header** (no local
  moments cache yet — chat avoids this via `chatMessageStream` which is a
  local DB lookup). Means edit fails offline. Future work: a moments local
  store that mirrors the `MessageLookup` pattern.
- **Push notification text is hardcoded English** (`"New moment posted"`).
  Should move to `compose-resources` / `stringResource` and probably be passed
  in by the caller (since recipients differ per moment).
- **No tests yet.** Mirror `homebase-chat/src/jvmTest/.../ChatMessageSenderServiceTest`
  patterns to add some.

## Open items

- [ ] **Backend registration of `MomentsAppId`** — push notifications will be
      dropped until the homebase server knows about the app id. Same story
      for whatever drive-permission record needs to exist for transit.
- [ ] **Confirm fileType=7050** does not collide with anything in the broader
      Homebase ecosystem. If there's a central registry (e.g. on the
      odin-services side), align with it.
- [ ] **Localize push notification text** — see "v1 cuts" above.
- [ ] **Pre-existing build break in chat nativeMain** — when I ran
      `./gradlew :homebase-core:compileCommonMainKotlinMetadata` to verify the
      new code, `homebase-chat/src/nativeMain/.../LocalVideoServer.native.kt`
      failed to compile (Ktor server APIs not resolving on native — `ApplicationCall`,
      `embeddedServer`, `routing`, `respond`, `respondBytes`, `CIO`, etc.).
      Unrelated to this work, but it blocks a full build verify on iOS until
      fixed. Verify on JVM/Android with
      `./gradlew :homebase-core:compileKotlinJvm :homebase-core:compileDebugKotlinAndroid`
      instead.

## Quick verification

```bash
# JVM-only smoke compile (avoids the unrelated nativeMain break in chat)
./gradlew :homebase-core:compileKotlinJvm
```

---

## MomentsRecipientLookupService

### Public surface

```kotlin
class MomentsRecipientLookupService(...) {
    val recipients: StateFlow<List<MomentsRecipient>>
    fun start()
    suspend fun recordUsed(recipient: MomentsRecipient)
}
```

`MomentsRecipient` is a sealed interface (`Individual` | `Group`) — see
`MomentsRecipient.kt`. It carries an opaque per-emission `MomentsRecipientId`
(random Uuid). The id is **not persistable** by callers; it changes on every
re-emission.

### Behavior

- Subscribes to `ContactService.contacts`, `ConversationStream.conversations`,
  and `MomentsRecipientMruStore.stableKeys` via `combine`. Re-emits whenever
  any source changes.
- Filters out: self-contact, with-self conversation, conversations in
  `Left` / `Removed` / `RejoinPending` / `Invalid` states, and conversations
  whose only remaining participant is the active user.
- Each contact → `Individual`. Each group conversation → `Group`. Each 1:1
  conversation → `Individual` (intentionally **not** deduped against a
  matching contact — both rows surface, per design).
- Sort: MRU-listed recipients first (in MRU order), then alphabetical by
  display name (case-insensitive).
- `recordUsed(recipient)` looks up the per-emission `id → stableKey` map
  built during the most recent emission and bumps that key in the MRU store.
  Stable keys are `contact:<odinId.domainName>` and `conversation:<uuid>`.

### MomentsRecipientMruStore

- One `KeyValue` row at Uuid `…0a0203` (the `0a02xx` namespace `MomentsPreferences`
  uses).
- Stores newline-joined stable keys (max 20 entries).
- API: `bump(stableKey)` moves to the front, `forget(stableKey)` removes.
- Exposes `stableKeys: StateFlow<List<String>>` so the lookup service rebuilds
  ordering reactively.

### Open items

- [ ] **Stale MRU entries**: if a contact / conversation is removed, its
      stable key stays in the MRU list (just doesn't sort anything to the
      front since no live recipient matches it). Acceptable for v1; a periodic
      cleanup pass could call `mruStore.forget(...)` for keys not in the
      current snapshot.
- [ ] **Search / filter**: lookup currently returns the full list. If the
      contact list grows large enough that filtering at the source becomes
      worth it, move into the service.
