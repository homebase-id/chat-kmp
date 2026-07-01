# homebase-upload — the one client upload pipeline

Every client media upload (chat, moments, vault, stickers, location, conversation files)
goes through **one** feature-agnostic pipeline here, so the payload-cache, fail-soft, and
seed policies live in exactly one place. This is the outcome of issue **#844**.

## The spine

`UploadService.upload(spec)` (create) and `UploadService.updateFile(spec)` (update) own the
same sequence and nothing else:

```
encrypt the plaintext bundle  →  encrypt the metadata content  →  build the request  →
durable outbox enqueue (the success gate)  →  seed the payload cache  →  optimistic local write
```

Callers hand in a **plaintext** `PayloadBundle`; the service encrypts it (`encryptor.encryptBundle`).
Feature-specific prep (source resolve, HEIC convert, video poster, recipient lookup) stays in the
feature and is passed in already-resolved. `UploadService` never contains feature logic.

**Fail-soft (deliverable A):** if a raw pre-encryption source is gone at encrypt time (swept,
evicted, permission revoked) the service returns `UploadOutcome.SourceMissing` and enqueues
**nothing** — no doomed outbox row. The caller re-picks. Encryption runs *before* enqueue
everywhere, which is what makes this clean.

## The guard

`ArchitectureTest."encryptBundle is confined to the shared upload pipeline"` fails the build if
`encryptBundle` is called anywhere but `UploadService` / `PayloadBundleEncryptionService`.
`encryptBundle` is the true "I'm doing a payload upload" signal, so anchoring on it is what makes
the guard meaningful. (A rule on the *request types* was unworkable — header-only system records
and the outbox rekey plumbing legitimately build `UploadFileRequest`/`UpdateFileByUniqueIdRequest`,
and a text rule can't tell payload-bearing from header-only.)

## The documented exceptions

Three production sites are allow-listed in the guard. Each is deliberate, not an oversight:

| Site | Why it's left out |
|------|-------------------|
| `ChatMessageSenderService.updateMessage` (edit / amend-pending-create) | Re-encrypts an **in-memory text-overflow** payload and mixes it with **already-encrypted** recovered media, then surgically amends a queued outbox row. No fit for the plaintext-bundle create/update shapes. |
| `ChatMessageSenderService.resendAsCreate` (resend) | Same pattern — text-overflow re-encryption mixed with pre-encrypted recovered payloads. Recovery plumbing, not a fresh media upload. |
| `ConversationService.updateConversationInternal` (conversation update) | A hot, multi-purpose mutation (archival / participants / leaveGroup / heal). The encrypt-a-new-group-avatar branch is rare; routing the whole method through `UploadService` is high blast radius for little benefit. |

Note: these bypass the *orchestration*, but they still call the same `encryptBundle`, so their
encrypted temps land in the same swept-safe `hb-temp/` dir (below) as everything else.

**If we ever want to remove these exceptions:** the first two need `UploadService` to model a *mixed
pre-encrypted + plaintext* payload set (and, for amend, an "amend a queued create" mode); the third
needs its rare encrypt branch split out from the hot mutation path, or the whole method carefully
migrated to `updateFile` with its heal/reuse and conditional-optimistic-write behavior preserved.

## Where temps live

`FileOperationsProvider.writeBytesToTempFile` writes every raw and encrypted upload temp into
`<cacheDir>/hb-temp/` on all platforms. `CacheSweeper` **keeps** `hb-temp` on the startup /
"Clear caches" sweep — an offline-pending upload's encrypted payload lives there until the send
completes, and deleting it mid-flight would break the send — and only wipes it on full logout.
FFmpeg scratch is separate: it goes to the cache root and is swept as reclaimable (it's large and
transient, the opposite durability need).
