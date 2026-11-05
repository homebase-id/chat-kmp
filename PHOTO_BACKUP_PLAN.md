# Photobase: background backup and simple gallery — plan (chat-kmp)

Status: **Built, unproven on devices** (see `PHOTOBASE_OVERNIGHT_REPORT.md`). Date: 2026-11-04.

Scope: background photo and video backup in chat-kmp (Android and iOS), plus a simple read-only
gallery as the Photobase add-on app (all four platforms). This is Phase 0 of Photobase; the
standalone app and everything in §11 "Out of scope" come later.

Goal: keep the phone's photo and video library backed up to the user's Homebase drive, regularly
and without the user opening the app, within what each OS actually allows.

The core fact everything below follows from: **neither OS lets an app decide when it runs.** A
reliable backup is therefore a stack of independent triggers that all call one idempotent,
resumable `PhotoBackupRun`, plus a full catch-up every time the app comes to the foreground.
Missing a trigger must only ever cost latency, never a photo.

Sources: Immich mobile (`immich/mobile/`, HEAD 8cdf9ff64), Ente Photos mobile
(`ente/mobile/apps/photos/`), Apple and Android developer docs (URLs in §13). Immich and Ente are
AGPL — this plan uses their *behaviour* only; no code is to be copied.

## Decisions

| Topic | Decision |
|---|---|
| Drive | Photos drive, `SelectiveShare` sharing model |
| App | Photobase add-on with its own newly minted `appId`; `PHOTO_APP_ID` is removed |
| Module | `homebase-photobase` |
| Photo IDs | `uniqueId` calculated from the BLAKE3 content hash, not keyed (§3.2) |
| Photo quality | Full quality; compression options are v2 |
| Videos | Handled the way chat-kmp does today (HLS), including re-encoding rotated videos; reconsidered in v2 |
| Edited photos | Saved as a separate photo; reconsidered in v2 |
| Live Photos | One Homebase file with two payloads; the gallery shows the still; playback is v2 |
| Motion photos | Uploaded as the original single file; playback is v2 |
| Gallery platforms | All four, read-only; backup runs on Android and iOS only |
| Backup wake-ups | The app schedules recurring silent pushes through odin-core, with an adjustable interval (§6.2) |
| Sending uploads | The ledger feeds one shared Outbox, with one upload slot reserved for chat (§3.4) |
| Gallery data | v1 queries the server directly, like Filesync; the Photos drive is never mounted or synced to the device; reconsidered in v2 (§3.5) |
| Push entry | `onPushArrived` only calls `PhotoBackupTrigger.onWake()`, so backup can be lifted out later (§3.3) |

---

## 1. Principles

1. **One backup run, many triggers.** Every trigger (media change, periodic job, push, app
   foreground, "Back up now") calls the same `PhotoBackupRun.run(budget)`. The run never assumes which
   trigger called it.
2. **Change detection uses OS change counters, not timestamps.** iOS `PHPersistentChangeToken`,
   Android MediaStore generations. Timestamps (capture date, date added) miss assets that arrive
   with old dates (AirDrop, restores, downloads) and miss edits.
3. **Checkpoint only after success.** The change-token / generation is saved only once every asset
   it covers is recorded in the local ledger. An interrupted run redoes work instead of skipping
   it (Immich: `lib/domain/services/local_sync.service.dart:113,144`).
4. **A ledger, not a cursor, decides what's left.** A local table records every device asset's
   state. "What to upload" is a query over the ledger, so failed items are retried automatically
   on the next run rather than being stranded behind a cursor.
5. **Content-hash dedupe.** The Homebase `uniqueId` is derived from the BLAKE3 hash of the
   content (§3.2), so re-running anything is safe and the same photo from two devices lands once.
6. **Work in small committed units under an explicit budget.** Every run gets a deadline from the
   trigger (≈25 s, a few minutes, or open-ended) and stops cleanly before it.
7. **The background task must not finish before the upload does.** Either the run awaits the
   upload, or the upload is handed to something that outlives the process (iOS background
   URLSession). Fire-and-forget into an in-process queue and then reporting the task complete
   loses the upload when the OS suspends the app.
8. **Foreground always catches up.** Opening the app runs a full `PhotoBackupRun` with no budget limit.

---

## 2. What chat-kmp already has to build on

| Piece | Where | Use for backup |
|---|---|---|
| Encrypted upload pipeline | `homebase-upload/.../UploadService.kt:42` (`upload(spec)`) | Upload each asset as a Homebase file |
| Multipart POST transport | `homebase-api/.../client/OdinApiProviderBase.kt:312-329` | Same; note it's one in-process POST per file (no chunking) |
| Outbox + retries | `homebase-api/.../sync/database/Outbox.sq`, `OutboxSync.kt` | Sends backup uploads; retry, storage-full and auth handling stay here |
| Scheduled push client | `homebase-api/.../client/notifications/ScheduledPushNotificationProvider.kt`, `OutboxSync.enqueueScheduledPush` | Recurring silent backup push (§6.2) |
| Add-on recipe with own app registration | `ADDING_ADDON_APPS.md`, `filesyncAppRegistration` in `AppConfig.kt` | Photobase app, drive and circle (§3.5) |
| Push wake path, Android | `androidApp/.../DriveFcmService.kt` (FCM → expedited worker + inline drain, ~10 s) | "Server says sync now" hint |
| Push wake path, iOS | `iosApp/iosApp/iOSApp.swift:199-215` (`didReceiveRemoteNotification` → `NotificationEntry.onPushArrived`) | Same |
| Background sync entry | `homebase-common/.../sync/BackgroundSyncOrchestrator.kt:32` (`syncIfAuthenticated`, waits for auth restore) | Pattern for "run with credentials from a cold background launch" |
| iOS short background grant | `homebase-api/.../platform/BackgroundExecutionAssertion.kt` (KDoc already says long uploads need a background NSURLSession) | Bridges the ~30 s after backgrounding |
| iOS continued processing (iOS 26) | `iosApp/iosApp/ContinuedProcessingBridgeImpl.swift` | "Back up now" with a system progress Live Activity |
| Gallery access + in-process observer | `homebase-common/.../gallery/AndroidGalleryManager.kt`, `IOSGalleryManager.kt`, `IOSGalleryLibraryObserver.kt` | Asset enumeration; observer only while running |
| iOS background modes | `iosApp/iosApp/Info.plist:49-55` has `fetch`, `remote-notification` | Need to add `processing` and BGTask identifiers |

Gaps to fill: no periodic/processing background tasks for backup, no MediaStore content trigger,
no change-token/generation tracking, no background URLSession. Chunked/resumable upload is v2.

---

## 3. Architecture

New module **`homebase-photobase`**, holding backup and the gallery, depending on `homebase-api`, `homebase-upload`,
`homebase-common`, `homebase-chat` (for the viewer, §3.5). Apps wire platform schedulers to it.

```
            triggers (platform)                         homebase-photobase (common)
 ┌──────────────────────────────────┐        ┌──────────────────────────────────────┐
 │ Android: content-URI job,        │        │ PhotoBackupRun.run(budget)           │
 │   periodic job, FCM, UIDT job    │──────▶ │   1. DeviceChangeSource.changes()    │
 │ iOS: BGAppRefresh, BGProcessing, │        │   2. Ledger.apply(changes)           │
 │   silent push, URLSession events,│        │   3. checkpoint token/generation     │
 │   BGContinuedProcessing          │        │   4. In parallel: hash, dedupe,      │
 │ Both: app foreground             │        │      prepare, Outbox uploads         │
 └──────────────────────────────────┘        │ BackupStatus (StateFlow for UI)      │
                                             └──────────────────────────────────────┘
```

Components (interfaces in commonMain, `expect`/`actual` or injected per platform):

- **`DeviceChangeSource`** — returns `Changes(inserted, updated, deleted, newCheckpoint, isFull)`
  since a stored checkpoint; falls back to a full enumeration when the checkpoint is missing or
  invalid. Also exposes `export(asset) → file path` (original bytes, iCloud download allowed).
- **`BackupLedger`** (SQLDelight table `DeviceBackupAsset`) — one row per device asset:
  `deviceAssetId, albumIds, mediaType, modifiedAt, size, contentHash?, uniqueId?, state
  (NEW|HASHED|QUEUED|UPLOADING|DONE|FAILED|SKIPPED|DELETED_ON_DEVICE), attempts, lastError,
  nextAttemptAt`. Plus a `BackupCheckpoint` key/value per identity + volume.
- **`Hasher`** — streams the original through `Blake3Hasher` and derives the `uniqueId` (§3.2);
  batched and cancellable.
- **`Uploader`** — turns `HASHED` rows into Homebase uploads after a dedupe check against the
  server by `uniqueId`. Two implementations: the shared Outbox (§3.4) and, while the iOS app is
  in the background, the background URLSession (§6.3).
- **`BackupScheduler`** — platform; arms/cancels triggers from `BackupSettings`.
- **`PhotoBackupTrigger`** — the only thing push handling knows about backup (§3.3).
- **`PhotoBackupRun`** — the orchestrator; a process-wide mutex so only one run is active; honours the
  budget; publishes `BackupStatus`.

### 3.1 `PhotoBackupRun` steps

In order:
1. Skip if another `PhotoBackupRun` is already going.
2. Wait for credentials to load (cold background launches restore them late).
3. Stop and record why if backup is off or blocked (network, battery, permissions).
4. Ask the OS what changed since the saved checkpoint, or list everything if there's no valid
   checkpoint.
5. Record new, changed and deleted photos in the ledger, in one transaction.
6. Save the new checkpoint, only after that transaction commits.

In parallel, until nothing is left or time runs out:
- **Hash:** hash several photos at once and calculate each `uniqueId`.
- **Dedupe:** check several photos at once against Homebase, and mark any already there as `DONE`.
- **Prepare:** prepare several photos at once (thumbnails, EXIF, encryption), with HLS video one at
  a time because it's heavy on CPU and disk.
- **Hand off:** give prepared items to the Outbox, or the iOS background URLSession when the app is
  in the background.
- **Upload:** start the Outbox with `send(force = true)`, because a plain `send()` is skipped during
  a background wake when the WebSocket is offline. Up to two uploads at once.
- **Record results:** mark each photo `DONE` when the server confirms it, or schedule a retry.

In order, at the end:
1. Stop starting new work when time runs out.
2. Wait for in-flight uploads to finish, or until the deadline.
3. Publish status for the UI and re-arm the triggers.

Order inside the run: oldest-first for the initial backup, **newest-first for incremental**
(people expect the photo they just took to appear). Photos before videos in short budgets.

### 3.2 Hashing and dedupe

**BLAKE3 everywhere backup writes, no SHA** — the same choice as the filesync Rust app, and it
reuses what chat-kmp already has:

| Piece | Where |
|---|---|
| BLAKE3 (commonMain, all four platforms, official test vectors) | `homebase-api/.../crypto/Blake3Hasher.kt`, `IncrementalHasherTest.kt` |
| Encrypt + hash in one streaming pass → `PayloadHash(storedHash, contentHash)`; the server verifies the upload | `homebase-api/.../crypto/PayloadHashing.kt` (`encryptWithPayloadHash`) |
| Download verification against the same hash | `PayloadDownloadService.streamVerified` |
| Precedent: BLAKE3 payload hashes, 4105 treated as success | `homebase-core/.../filesync/FilesyncUploader.kt:76`, `FilesyncUploaderTest.kt:38` |

`PayloadHashAlgorithm.Sha256` stays for existing chat files; backup never writes it.

**`uniqueId` comes from the content hash.** It's derived from the plaintext BLAKE3 digest of the
original bytes, so the same bytes produce the same id on every device. For a Live Photo it's
derived from both parts. Video is hashed from the original file, before HLS.
- Accepted tradeoff: the calculation is public, so anyone who can read the drive's `uniqueId`s can
  check whether the user holds a known photo.
- The scheme must be fixed before Phase 1 ships: changing it later re-hashes and re-uploads
  every backed-up asset.

**Two reads per uploaded asset.**
1. *Hasher:* plaintext BLAKE3 → `uniqueId` → dedupe check. Nothing is encrypted or written for an
   asset that is already on Homebase.
2. *Uploader:* `encryptWithPayloadHash(..., Blake3)` re-computes the plaintext digest while
   encrypting. **It must equal pass 1's digest**; if not, the asset changed in between — return
   the row to `NEW` instead of uploading new bytes under the old id. Videos go through chat-kmp's
   HLS path instead; how this check applies there is settled when that task is built.

**Dedupe across devices.**
- Before uploading: ask the server (query by `uniqueId` on the Photos drive; there's no local index
  in v1, §3.5). If present → mark `DONE`, no upload.
- Two devices racing on the same photo both pass the check; the server keeps one file per
  `uniqueId` per drive and rejects the second with `ExistingFileWithUniqueId` (4105,
  `ClientException.isExistingFileWithUniqueId()`). Backup treats 4105 as **success** → `DONE`,
  never a retry or failure.
- Known limit: only byte-identical copies dedupe. iCloud-synced originals match; a photo
  AirDropped as JPEG, recompressed by a messenger, stripped of location, or edited does not and
  lands as a second file. Perceptual/metadata matching is out of scope for v1.

**Edits.** Rows edited on the device (iOS "updated", Android `GENERATION_MODIFIED`) get re-hashed;
a new hash is saved as a separate photo in v1. How to handle edits properly is reconsidered in v2.

**Throughput is v2.** v1 ships on the existing Kotlin `Blake3Hasher`, a port of BLAKE3's
`reference_impl` (no SIMD, single thread), with no speed testing or gate.

> **v2: BLAKE3 optimization.** Measure MB/s on a mid-range Android phone and a real iPhone, recorded
> in `OPTIMIZE.md`. If needed, replace the port with a fast implementation (with an ARM NEON path)
> behind the same `IncrementalHasher` contract and test vectors. Candidates: the official C library
> (Recommended: NEON, cinterop on iOS, JNI on Android/desktop) or the official Rust crate (fastest,
> matches filesync, but adds a Rust toolchain).

### 3.3 Entry from a push wake

`NotificationEntry.onPushArrived` makes one call, `PhotoBackupTrigger.onWake()`, and knows nothing
else about backup, so backup can later be lifted out of chat-kmp. The interface lives in
`homebase-common`, because `homebase-photobase` depends on that module; `homebase-core` binds the
implementation in Koin.

- **Android:** `onWake()` only enqueues a separate backup worker with its own unique work name.
  Running the backup inside `DriveSyncWorker` would hold its `KEEP` slot, and pushes arriving
  meanwhile would lose their payloads.
- **iOS:** `onWake()` starts `PhotoBackupRun` alongside `captureAndUpload`, limited to what's left
  of the ≈30 s fetch window.

### 3.4 Ledger and Outbox

The ledger decides whether a photo is backed up; the Outbox only sends. There is no separate
backup outbox, because it would duplicate `OutboxSync`'s retry, storage-full and auth handling.

- `HASHED` rows enter the Outbox in small batches, so a whole library is never encrypted up front.
- One of the Outbox's three upload slots is reserved for chat, so backup can't hold up a message.
- Outbox success → ledger `DONE`. `OutboxItemDropped` → ledger back to `HASHED` with a backoff, so a
  drop never loses a photo.
- The iOS background URLSession bypasses the Outbox (§6.3).

### 3.5 Photobase add-on and gallery

- Follows `ADDING_ADDON_APPS.md`: onboarding, permissions, nav icon, settings toggle.
- Registers its own app, Photos drive (`SelectiveShare`) and circle, following
  `filesyncAppRegistration` in `AppConfig.kt`. Sharing UI is out of scope.
- Gallery, on all four platforms and read-only: tiny thumbnail first, then larger sizes.
- **Data:** v1 queries the server directly and pages through results, like the Filesync browser. The
  Photos drive is never mounted, so it stays out of `syncAll` on wake-ups and there's no local copy of
  tens of thousands of photo records. The cost: the gallery needs a network connection.
- **Refresh:** the gallery queries when it opens and from a refresh button in the top bar, on all
  four platforms. With no sync or WebSocket, that's how photos from other devices appear.
- **Activation:** the recipe's `OptionalDriveActivation` and `FooStream` assume a mounted drive, so
  they don't apply; follow Filesync's approach instead.
- **Grid:** a new control, kept simple, modelled on `MomentsAlbumGrid` (grouped by capture date).
  `MomentsAlbumGrid` itself is tied to Moments and isn't reused.
- **Viewer:** `FullScreenMediaViewer` as is, plus chat-kmp's video player. It lives in
  `homebase-chat` and is keyed by `messageId`, so `homebase-photobase` depends on `homebase-chat` and
  passes file IDs as message IDs. Untangle this before lifting Photobase out of chat-kmp.

---

## 4. Change detection per platform

### Android
- Android 11+ (API 30): store `MediaStore.getVersion(volume)` and the highest
  `GENERATION_MODIFIED` per volume. Delta = rows with `GENERATION_MODIFIED > stored OR
  GENERATION_ADDED > stored`. If the version string changes → full enumeration.
  (Android docs; Immich does exactly this: `android/.../sync/MessagesImpl30.kt:44-100`.)
- Android ≤10: always full enumeration (cheap compared with uploading).
- Deletions aren't in the generation delta: diff the ledger's asset ids against a full id list
  of selected albums; do this on foreground and in long runs, not in ≈25 s ones.
- Permissions: `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (13+), `ACCESS_MEDIA_LOCATION` plus
  `MediaStore.setRequireOriginal()` or GPS EXIF is redacted; handle partial access
  (`READ_MEDIA_VISUAL_USER_SELECTED`, 14+) by backing up only what's granted and saying so.

### iOS
- iOS 16+: store a `PHPersistentChangeToken`; `fetchPersistentChanges(since:)` gives inserted,
  updated and deleted local identifiers. On `persistentChangeTokenExpired`, no token, or limited
  library access → full enumeration. (Apple docs; Immich: `ios/Runner/Sync/MessagesImpl.swift:81-206`.)
- iOS 15 and below: full enumeration each run (fetch ids only, diff against ledger).
- Shared/iCloud shared albums are not in the change history — re-diff them on foreground only.
- Use `authorizationStatus(for: .readWrite)`; the old API reports `.authorized` for limited access.
- After a background launch, the first foreground run does a full enumeration (Immich found
  deltas can miss assets after a background launch: `app_life_cycle.provider.dart:68-72`).

---

## 5. Android triggers

| Trigger | Mechanism | Constraints | Notes |
|---|---|---|---|
| New photo/video | One-time WorkManager job with `addContentUriTrigger` on `MediaStore.Images/Video` EXTERNAL (and INTERNAL), descendants = true; update delay 30 s, max delay 5 min | network per settings; charging if set | Fires once — re-enqueue it at the **start** of every run and at app start. Not persisted across reboot; WorkManager re-registers on boot, but also re-arm on process start (Immich admits a lost trigger after process kill: `ImmichApp.kt:12-21`) |
| Safety net | Periodic WorkManager, 1 h (min 15 min), `ExistingPeriodicWorkPolicy.UPDATE` | network per settings | Calls the same run |
| Overnight catch-up | Periodic 6–12 h, requires charging + unmetered (+ idle) | charging lifts standby-bucket limits | Where the big first backup actually finishes |
| Server hint | Existing push path → `onPushArrived` → `PhotoBackupTrigger.onWake()` enqueues a separate expedited backup worker (§3.3) | — | Expedited quota can downgrade to deferrable (already observed, #987) |
| "Back up now" / first import | **User-initiated data transfer job** (API 34+, `setUserInitiated(true)`, `RUN_USER_INITIATED_JOBS`, notification required); on API <34 a WorkManager worker with `setForeground` (`dataSync`) | network | Must be started while the app is visible; not for automatic work |
| App foreground | Start `PhotoBackupRun` in the app scope | — | Full enumeration + deletions |

Budget handling: WorkManager stops a worker after ~10 min; budget the run at **9 min** and
return `Result.success()` with work remaining (next trigger continues). Use `Result.retry()` only
for "nothing uploaded because of a transient error", with exponential backoff from 1 min.

Foreground service: only inside the UIDT/`setForeground` path, type `dataSync`. Android 15 caps
`dataSync` at **6 h per 24 h** — handle `onTimeout()` by stopping within seconds.

Battery: Doze and standby buckets (Restricted after 8 days unused on 13+) will throttle all of
this. Offer, never force, the battery-optimisation exemption ("photo management" is an accepted
use case in Android's Doze docs) and link to dontkillmyapp.com for the phone's maker.

Constraints mapping: "Wi-Fi only" → `NetworkType.UNMETERED` on the job (so it doesn't wake just to
decline — Ente uses CONNECTED and skips in app code: `lib/utils/network_util.dart:4-16`);
"charging only" → `setRequiresCharging(true)`; always `setRequiresBatteryNotLow(true)`.

---

## 6. iOS triggers

### 6.1 Background tasks
- `BGAppRefreshTask` `…backup.refresh`: earliest +15 min; ≈30 s total. Use for: apply changes to
  the ledger, hash a few photos, **hand uploads to the background URLSession**. Budget 25 s.
- `BGProcessingTask` `…backup.processing`: `requiresNetworkConnectivity = true`,
  `requiresExternalPower = true` (also disables the CPU monitor); several minutes, usually
  overnight on the charger. Hashing and big batches go here.
- Register both in `application(_:didFinishLaunchingWithOptions:)` before launch finishes. Add
  `processing` to `UIBackgroundModes` and both ids to `BGTaskSchedulerPermittedIdentifiers`.
- **Resubmit each request at the start of its handler** (one request = one launch), and on every
  app launch. If the refresh task fires while a processing task is running, end it immediately
  with success so the time isn't counted against us.
- Always set `expirationHandler` → cancel the run, save state, `setTaskCompleted` within ~2 s.

### 6.2 Silent push
- A `content-available` push reaches `PhotoBackupTrigger.onWake()` through the existing
  `didReceiveRemoteNotification` → `onPushArrived` path (§3.3); the run shares the ≈30 s window
  with chat sync and location capture. Apple: no more than 2–3 per hour; not delivered after the user
  force-quits; held pushes are collapsed to the newest. Treat as a hint only.
- **The app schedules these itself** with odin-core's scheduled push: `OutboxSync.enqueueScheduledPush`
  with `silent = true` and a `recurrenceInterval`. No new API client is needed.
- The interval is a setting. The API floor is 5 minutes, faster than Apple's 2–3 per hour; Proposed:
  default about 1 hour, minimum 30 minutes.
- One shared schedule per identity, not one per device, because a push is expected to reach all of
  the identity's phones.
- Silent pushes stay on the chat `appId`, not Photobase's; pushes under any other `appId` are
  expected to be rejected. Confirm by test.
- Confirm that a scheduled silent push actually wakes a backgrounded iPhone; so far only one-shot,
  visible event reminders use this API.

### 6.3 Background URLSession uploads (the main iOS lever)
- The OS daemon keeps uploading after the app is suspended or even terminated, then relaunches
  the app via `handleEventsForBackgroundURLSession` to report results.
- Requirements: **upload from a file**, one session identifier, recreate the session on relaunch,
  queue in batches (≈50–100), not thousands. Tasks queued from the background are discretionary
  and each background relaunch adds a growing delay before new tasks start.
- What this needs in chat-kmp: today an upload is an in-process Ktor multipart POST
  (`OdinApiProviderBase.kt:312-329`). Add a path that **writes the fully encrypted multipart body
  to a file** (headers + encrypted payloads + thumbnails) and hands `(URLRequest, bodyFile)` to a
  Swift `BackgroundUploadBridge`. Auth headers/cookies must be on the request (they can't be
  refreshed mid-transfer); completion callbacks update the ledger and remove the body file.
- `BackgroundExecutionAssertion` stays for small in-process work; it's the documented ceiling
  (`BackgroundExecutionAssertion.kt` KDoc) this replaces for media.

### 6.4 "Back up now" and the first big backup
- `BGContinuedProcessingTask` (iOS 26) via the existing `ContinuedProcessingBridgeImpl.swift`:
  user-started only, shows system progress, cancellable; must report progress or it is killed
  first. Apple explicitly says not to use it for automatic backups or photo syncing.
- Fallback on <26 and for very large libraries: a "keep the app open" backup screen that keeps the
  screen on and dims it (Ente's "Backup mode", `lib/ui/home/large_backup_screen.dart`), plus
  telling the user to plug in.

### 6.5 PhotoKit Background Resource Upload extension (iOS 26.1+) — evaluate, don't build yet
- The only API where **the system watches the photo library and runs the app's extension when it
  changes**, and the system does the upload (including iCloud originals), with retries.
- Fit problems for Homebase, to resolve before adopting:
  - The request body is the **raw asset bytes** only; Homebase uploads are encrypted multipart
    (metadata + encrypted payload + thumbnails). Would need a server endpoint that accepts a raw
    (or client-encrypted-in-extension?) stream and a separate metadata call — needs odin-core
    owner input.
  - Needs full library access, a fixed `BackgroundUploadURLBase` in Info.plist (reportedly only
    used for a connectivity check), and server support for the IETF resumable-upload draft
    (`OPTIONS`/`Upload-Limit`, `104` interim response).
  - Reported: replaced in iOS 27 by `PHBackgroundResourceUploadJobExtension`. Verify on Apple's
    pages before design work; this came from a research pass, not read first-hand.
- Decision gate: spike in Phase 4. If the server can accept it, this becomes the primary
  automatic iOS path and BGTasks become catch-up.

### 6.6 Not doing
- Significant-location-change / region-monitoring wake-ups (OneDrive, PhotoSync, Nextcloud used
  them): App Review 2.5.4 / 5.1.5 risk, needs "Always" location, and a May 2026 developer report
  says they no longer relaunch a force-quit app on iOS 26.4.
- Silent audio, Live Activity or VoIP keep-alive hacks.

---

## 7. Uploading large files within budgets

- Today Homebase upload is one POST per file. A 2 GB video over a weak link won't fit an Android
  9-min window and is fragile even on a background URLSession.
- **v2, server ask (odin-core, needs owner):** chunked/resumable upload for payloads (Ente keeps
  20 MB parts with per-part state in SQLite and resumes after interruption:
  `lib/db/upload_locks_db.dart:109-135`, `lib/module/upload/service/multipart.dart:258-287`).
  Ideally the IETF resumable-upload draft so §6.5 can reuse it.
- In v1: a failed upload is retried from the start; photos first; videos only on unmetered + charging or in the foreground; per-file
  size ceiling for short budgets (Ente skips background videos unless resumable is on:
  `remote_sync_service.dart:658-659`).
- Encrypted temp bodies live in cache, are keyed by `uniqueId`, survive process death (so an
  iOS relaunch can resume), and are swept when the ledger row reaches `DONE`/`FAILED`.

---

## 8. Settings and user-facing status

Settings (per identity): backup on/off · which albums (default: Camera / Recents) · Wi-Fi only
(default on) · videos on cellular (default off) · charging only (default off) · backup push interval
· "only back up new items from now".

Status screen: last successful run time, pending count, uploading now, failures with reason and
"retry", and **why it's paused** (no network, waiting for Wi-Fi, low battery, Low Power Mode,
Background App Refresh off, battery optimisation on, limited library access, storage full).

User guidance shown in-app (every serious app ends up here — Google Photos, Dropbox, OneDrive,
Immich, Ente all say it): don't swipe the app away; keep Background App Refresh on; plug in and
keep the app open for the first backup; on Android allow unrestricted battery for best results.

Notification: low-importance, only for ongoing user-started transfers (Android UIDT requires one)
and for "N items keep failing".

---

## 9. Failure handling

- `OutboxSync` retries sends. When it drops a row, the ledger's `attempts` + `nextAttemptAt` back
  it off (1 min → 6 h cap) before it re-enters the Outbox; permanent errors (unsupported type, asset
  gone, too large) → `SKIPPED` with reason, not retried.
- Storage full on the Homebase drive → pause all uploads, surface it, retry on foreground.
- Auth lost → pause; never call logout from backup code.
- Asset deleted on device: mark `DELETED_ON_DEVICE`; **never delete from Homebase automatically**
  (backup, not mirror). Proposed later option: "remove from Homebase when deleted on phone", off.
- Foreground and background runs share one mutex; the iOS URLSession is the only thing allowed
  to keep uploading while the app is suspended, and the ledger's `UPLOADING` state records the
  task id so a foreground run doesn't double-send.

---

## 10. Testing

- **jvmTest (common):** ledger state machine; checkpoint saved only after commit; deletion diff;
  dedupe by `uniqueId`; 4105 → `DONE`; pass-2 digest mismatch → `NEW`; Outbox drop → back to
  `HASHED`; budget/deadline stop; resume after a kill mid-run (fake `DeviceChangeSource`, fake `Uploader`, virtual time).
- **commonTest on all four platforms:** `uniqueId` derivation is identical on each.
- **Gallery:** tests on all four platforms.
- **Android instrumentation (emulator):** insert images via MediaStore → content trigger fires;
  `adb shell cmd jobscheduler run -f` to force jobs; `adb shell dumpsys deviceidle force-idle` for
  Doze; `am set-standby-bucket … rare/restricted`; reboot re-arm.
- **iOS (simulator + device):** `e -l objc -- (void)[[BGTaskScheduler sharedScheduler]
  _simulateLaunchForTaskWithIdentifier:@"…"]` in LLDB to launch tasks; `_simulateExpiration…` for
  expiry; background URLSession resume after the app is killed by the debugger (not force-quit);
  limited-library mode. PhotoKit upload extension is not available in the simulator.
- **Soak test:** a week on real phones (one Pixel, one Samsung, one iPhone) with the app not
  opened, counting the delay from capture → on Homebase. That number is the real success metric.

---

## 11. Task list

### Phase 1: Backup works while the app is open
- [x] Create `homebase-photobase` module
- [x] Add ledger and checkpoint tables
- [x] Derive `uniqueId` from the content hash
- [x] Android change detection
- [x] iOS change detection
- [x] Full enumeration fallback
- [x] Deletion detection
- [x] Build the Hasher
- [x] Build dedupe check
- [x] Read capture date and location from EXIF
- [x] Generate thumbnails on upload
- [x] Back up Live Photos as one file with two payloads
- [x] Derive the Live Photo `uniqueId` from both parts
- [x] Back up videos through chat-kmp's HLS
- [x] Feed the Outbox from the ledger
- [x] Reserve one Outbox upload slot for chat
- [x] Support several upload streams for bulk import
- [x] Build `PhotoBackupRun`
- [x] Run hashing, dedupe, preparing and uploading in parallel
- [x] Run a `PhotoBackupRun` on app foreground
- [x] Handle failures and retries
- [x] Schedule, update and cancel the recurring backup push
- [x] One shared schedule per identity, not one per device
- [x] Push interval setting
- [x] Backup settings screen
- [x] Backup status screen
- [x] Media permissions flow

### Phase 2: Simple gallery add-on
- [x] Mint the Photobase `appId`
- [x] Register the Photobase app, drive and circle
- [x] Remove the unused `PHOTO_APP_ID`
- [x] Add-on setup (onboarding, permissions, nav icon, settings toggle)
- [x] Query the Photos drive from the server, paged
- [x] Query on open, plus a refresh button in the top bar
- [x] New gallery grid by capture date, modelled on `MomentsAlbumGrid`
- [x] Tiny thumbnail first, then larger sizes
- [x] Use `FullScreenMediaViewer` as is
- [x] Reuse chat-kmp's video player

### Phase 3: Android background
- [x] Set wake-time budget and order alongside chat sync
- [x] Add `PhotoBackupTrigger` and call `onWake()` from `onPushArrived`
- [x] Android: `onWake()` enqueues a separate backup worker
- [x] New-photo trigger
- [x] Periodic job
- [x] Overnight job
- [x] "Back up now"
- [x] Battery guidance

### Phase 4: iOS background
- [x] Register background refresh task
- [x] Register overnight processing task
- [x] iOS: `onWake()` starts `PhotoBackupRun` alongside `captureAndUpload`
- [ ] Confirm a scheduled silent push actually wakes a backgrounded iPhone
- [x] Write encrypted upload body to file
- [x] Background URLSession bridge
- [x] Handle upload results on relaunch
- [x] "Back up now"
- [x] "Keep the app open" backup screen
- [x] Remove or wire up the unused `fetch` mode
- [ ] Spike PhotoKit background upload against Homebase

### Testing
- [x] Ledger and `PhotoBackupRun` tests
- [x] BLAKE3 and `uniqueId` tests on all four platforms
- [x] Gallery tests
- [ ] Android background tests
- [ ] iOS background tests
- [ ] One-week soak test on real phones

### v2
- [ ] Measure BLAKE3 speed on real phones and replace the port with a fast implementation if needed
- [ ] Resume huge videos mid-upload (client and odin-core)
- [ ] Compression options
- [ ] Storage management
- [ ] Consider how to handle edited photos
- [ ] Live Photo and motion photo playback
- [ ] Reconsider how videos are stored, including rotated-video quality and offering the original file
- [ ] Reconsider syncing the Photos drive to the device instead of querying the server

### Out of scope
- Standalone Photobase app and SDK split
- Unencrypted local copies, unencrypted server option
- Wider format support, including RAW
- Import from Google Takeout and iCloud
- Albums, search and map views
- Native rendering and fast-scrolling work
- Viewer upgrade and image editor
- ML
- Sharing and collaboration

---

## 12. Open questions

1. Could odin-core accept the iOS upload extension's raw-bytes request, with metadata sent
   separately? Answered by the Phase 4 spike.
2. Multiple devices per identity: do we track which device each file came from?
3. How does the server handle two phones creating the shared push schedule at the same time?

## 13. References

Apple
- Background tasks strategy: https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app
- BGProcessingTaskRequest: https://developer.apple.com/documentation/backgroundtasks/bgprocessingtaskrequest
- WWDC19 707 Advances in App Background Execution: https://developer.apple.com/videos/play/wwdc2019/707/
- WWDC20 10063 Background execution demystified (transcript): https://nonstrict.eu/wwdcindex/wwdc2020/10063/
- WWDC25 227 Finish tasks in the background: https://developer.apple.com/videos/play/wwdc2025/227/
- BGContinuedProcessingTask: https://developer.apple.com/documentation/backgroundtasks/performing-long-running-tasks-on-ios-and-ipados
- Background URLSession: https://developer.apple.com/documentation/foundation/downloading-files-in-the-background
- PHPersistentChangeToken: https://developer.apple.com/documentation/photos/phpersistentchangetoken
- WWDC22 Explore PhotoKit change history: https://developer.apple.com/videos/play/wwdc2022/10132
- PhotoKit background upload: https://developer.apple.com/documentation/photokit/uploading-asset-resources-in-the-background
- Silent push: https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app
- Limited library: https://developer.apple.com/documentation/photokit/delivering-an-enhanced-privacy-experience-in-your-photos-app
- App Review Guidelines: https://developer.apple.com/app-store/review/guidelines/

Android
- WorkManager define work: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- JobInfo.Builder (content triggers, user-initiated): https://developer.android.com/reference/android/app/job/JobInfo.Builder
- Data transfer options: https://developer.android.com/develop/background-work/background-tasks/data-transfer-options
- User-initiated data transfer: https://developer.android.com/develop/background-work/background-tasks/uidt
- FGS types / timeout: https://developer.android.com/develop/background-work/services/fgs/service-types , https://developer.android.com/develop/background-work/services/fgs/timeout
- Power limits by bucket: https://developer.android.com/topic/performance/power/power-details
- Doze & standby: https://developer.android.com/training/monitoring-device-state/doze-standby
- MediaStore generations: https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#GENERATION_ADDED
- Partial photo access: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- https://dontkillmyapp.com/

Apps
- Immich mobile backup docs: https://docs.immich.app/features/mobile-backup
- Ente backup docs: https://ente.com/help/photos/features/backup-and-sync/
- Immich discussion on the PhotoKit extension: https://github.com/immich-app/immich/discussions/23245
- Google Photos iOS backup help: https://support.google.com/photos/answer/6193313?co=GENIE.Platform%3DiOS
- OneDrive mobile troubleshooting: https://support.microsoft.com/en-us/onedrive/troubleshoot-onedrive-mobile-app-problems
- Dropbox camera uploads: https://help.dropbox.com/create-upload/camera-uploads-overview

Unconfirmed (check before relying on): force-quit cancelling background URLSession transfers
(widely stated, not found in Apple's text); iOS 26 behaviour of location wake-ups after
force-quit; current Play policy wording for the battery-optimisation exemption.
