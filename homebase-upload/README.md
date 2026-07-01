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

## Where temps live — two folders, split at the encryption boundary

#844's rule is *"the durability boundary begins at encryption; everything before it is
disposable."* So upload temps live in **two** dirs under the app cache dir, swept differently:

| Dir | Contents | Written by | Cleaned |
|-----|----------|-----------|---------|
| `<cacheDir>/upload-temp/` | RAW, pre-encryption source | `writeBytesToTempFile` (senders) | **Disposable** — swept on **every** startup / "Clear caches" / logout (it's untracked). Self-heals, can't grow. A source gone at send time just fails soft (re-pick). |
| `<cacheDir>/outbox-temp/` | ENCRYPTED, ready-to-transmit | `writeBytesToOutboxTempFile` (`encryptFile`) | **Durable** — each file is referenced by an outbox row until sent. `CacheSweeper` **KEEPs** it on startup / "Clear caches" (never deletes a pending send's payload); only logout wipes the whole dir. |

### When each folder is cleaned

- **`upload-temp/`** — reaped by the `CacheSweeper` on **every app startup**, on **"Clear caches"**,
  and on **logout**. It self-heals continuously and cannot grow. (Senders also delete their own
  source temp after use, but the sweep is the real safety net, so a sender that forgets can't leak.)
- **`outbox-temp/`** — cleaned along the **outbox's own lifecycle** (`homebase-api` sync layer,
  *outside this module* — it's the hand-off point where we deliver the encrypted file to the
  outbox): each file is deleted when its send **succeeds** (`cleanupPayloadTempFiles`) or when its
  row is **dropped** after ~48h of failed retries (`cleanupPayloadsForDroppedRow`), and the whole
  dir is wiped on **logout**. The `CacheSweeper` never touches it (a pending/offline send's payload
  must survive). Its **self-heal for crash-orphans** (a process death between server-ack and the
  outbox's per-file cleanup leaves a file with no live row) is **built + tested**:
  `OutboxSync.reapIdleOutboxTemps`, wired fire-and-forget into the post-auth hook — **when the
  outbox is idle (`count() == 0`), it deletes `outbox-temp/` files older than 24h.** The two gates
  make it provably safe: *idle* means nothing is referenced (an offline-pending send keeps
  `count() > 0`), and the *age floor* means a temp for a send being created right now (row not yet
  inserted) is too young to touch.

  The **only** thing left unbuilt is a strictly-better variant for one pathological case — a user
  whose outbox *literally never* drains to empty never triggers the idle reap. Closing that would
  take a per-file **reference-aware** reap (delete `outbox-temp/` files no live row references,
  reusing `cleanupPayloadsForDroppedRow`'s path extraction). Low priority; not built.

FFmpeg scratch is a third case: it goes to the cache **root** and is swept as reclaimable (large +
transient — same disposable class as `upload-temp/`, opposite of `outbox-temp/`). Ideally it would
live *inside* `upload-temp/` for one tidy disposable-scratch folder, but it's written by an external
process to self-resolved per-platform paths (not via `writeBytesToTempFile`), and moving it is a
4-platform change for zero functional gain (both are already swept identically) that would also cost
the per-type Storage-screen labels (`compressed_`/`input_`/`hls_`). Not worth the effort.
