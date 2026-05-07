# MomentsPostSenderService — handoff

Branch: `moments-framing`

## What this is

A new service in the moments app that lets a caller post a moment (description +
N image/video attachments) by reusing the chat module's attachment-build and
payload-encryption pipeline, then enqueueing an `UploadFileRequest` to the
existing `OutboxSync` so the low-level transport (`DriveOutboxUploader` →
`DriveUploadProvider`) does the actual upload. The low-level transport is
unmodified.

## Files added

```
homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/
├── MomentsProtocol.kt           # MomentsAppId, MomentPostFileType=7050, version
├── MomentPostContent.kt         # @Serializable { version, description }
└── MomentsPostSenderService.kt  # postMoment(...) → PostMomentResult
```

## File touched

`homebase-core/src/commonMain/kotlin/id/homebase/core/di/AppModule.kt` —
added `singleOf(::id.homebase.core.moments.services.MomentsPostSenderService)`
right after the `MomentsPreferences` line.

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
```

Internally:

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

## What is NOT included (deliberate v1 cuts vs. ChatMessageSenderService)

- **No optimistic write** — moment will only appear after the outbox drains and
  sync replays it back. Add an `OptimisticWriter.writeNewFile(...)` call after
  the enqueue if you want immediate UI feedback.
- **No outbox chaining** — `dependencyUniqueId` is hardcoded `null`. Each
  moment is independent. If we want strict send order while offline, add a
  per-user (not per-conversation) chain like
  `ChatMessageSenderService.lastEnqueuedPerConversation`.
- **No editing / no version-tag handling** — there's no `updateMoment(...)`.
- **No reply / no status-message / no forwarding.**
- **Push notification text is hardcoded English** (`"New moment posted"`).
  Should move to `compose-resources` / `stringResource` and probably be passed
  in by the caller (since recipients differ per moment).
- **No tests yet.** Mirror `homebase-chat/src/jvmTest/.../ChatMessageSenderServiceTest`
  patterns to add some.

## Wiring it up to the UI (next step)

The screen and ViewModel exist but have no compose action yet:

- `homebase-core/.../ui/screens/moments/MomentsScreen.kt:67` — the FAB calls
  `onCreateMoment()`. Currently the create flow is a no-op.
- `homebase-core/.../ui/screens/moments/MomentsViewModel.kt` — needs to take
  `MomentsPostSenderService` via constructor injection (add to the Koin
  `viewModel { MomentsViewModel(...) }` declaration in
  `AppModule.kt:368`) and expose a `postMoment(description, attachments, recipients)`
  action that calls `momentsPostSenderService.postMoment(...)`.
- A composer screen needs to be built (image/video picker + description
  TextField + recipient picker + Post button). Pattern to follow:
  `homebase-chat/.../ui/.../MessageComposer.kt` and how attachments flow into
  `ChatMessageSenderService` via `AttachmentInput`.
- Once a moment is enqueued, the screen also needs a feed query that filters
  `momentsLabeledDrive` by `appData.fileType == MomentsProtocol.MomentPostFileType`
  to render the timeline (currently `MomentsScreen.samplePosts()` is hard-coded).

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
