# Photobase gallery: local-first loading — plan

**Status: proposed, nothing built.** This reverses the v1 decision in `PHOTO_BACKUP_PLAN.md` §3.5
that the Photos drive is never synced, so it needs sign-off, along with the open questions in §6.

## 1. Goal

- The first thing every tile shows is the tiny preview embedded in the file metadata.
- Recent thumbnails come from the device, not the server.
- The server is only asked for thumbnails when scrolling back in time.

## 2. How it works today

| Step | Today |
|---|---|
| Data | Network only: the Photos drive isn't synced, and pages are held in memory (`PhotoGalleryRepository`) |
| Opening | Queries the newest 40 photos by capture date, and each open starts over |
| Scrolling | Fetches the next 100 near the end of the grid |
| Tiny preview | Shown blurred straight away, but only after the page query returns, because it comes in that response |
| Sharp thumbnail | 320px from the server, through the 300 MB thumbnail cache shared with chat |
| Other devices' photos | Appear only on reopen, refresh or re-sort |
| Pre-downloading | None, for the gallery or chat |

## 3. The chat pattern to follow

- **First sync:** `DriveSyncPolicy` pulls a recent window (chat: 14 days) with no saved cursor until
  the windowed crawl starts (`AppModule.kt`, `DriveSync.kt`).
- **Later syncs:** a saved cursor picks up every change, including deletes.
- **Live updates:** the WebSocket writes new files straight to the local database.
- **Older history:** `ChatServerHistory.fetchOlderPage` fetches a page on request and saves it
  locally with `cursor = null`, so the sync cursor doesn't move.
- **Images:** sync stores metadata only; thumbnails download when something needs to show them.

## 4. Proposed changes

### 4.1 Sync the Photos drive

- Mount the drive with a sync policy, and have the gallery read from the local database.
- Tiny previews then appear without waiting for the network, and recent photos work offline.

### 4.2 Fetch older pages on scroll

- Add a backfill modelled on `ChatServerHistory`, triggered by scrolling rather than a button.
- Save fetched pages locally with `cursor = null`.

### 4.3 Give videos a tiny preview

Video processing makes a 20px preview, but `omitPlaintextPreviews = true` drops it
(`UploadService.kt`) and a video's `PhotoContent` holds only its name. Video tiles should therefore
stay blank until the sharp thumbnail loads; this comes from reading the code, not from a device.

- Copy the preview into the encrypted `PhotoContent`, as photos already do, so the drive stays zero
  knowledge.
- Build the metadata after video processing, since it's currently built before.
- Decide what to do about videos already backed up without one.

### 4.4 Separate thumbnail cache

Same code path and same encrypted thumbnails, with Photos drive files in their own disk cache so a
photo library can't push out chat images.

```kotlin
private val photoThumbDiskCache = DiskCache.Builder()
    .directory("$directory/homebase-photo-thumbs-v1".toPath())
    .fileSystem(fileSystem)
    .maxSizeBytes(PHOTO_THUMB_CACHE_BYTES)
    .build()

private fun thumbCacheFor(driveId: Uuid) =
    if (driveId == photosDriveId) photoThumbDiskCache else thumbDiskCache
```

- **Thumbnail calls:** `getThumbBytesRaw`, `cacheThumbBytesEncrypted` and `rekeyCachedFile` use
  `thumbCacheFor`. Check whether `evictFile` and `readThumbThrough` need the drive ID passed in.
- **Housekeeping:** add it to `clearCaches`, `getCacheStats` and `CacheAudit`'s directory list.
- **Layering:** `homebase-api` can't depend on Photobase, so `AppModule` passes in the drive ID and size.
- **Limits:** pre-downloads get their own small limit, so they don't hold up chat images waiting on
  the shared 30-slot `thumbnailSemaphore`.

### 4.5 Put thumbnails on the device ahead of time

New work: nothing does this today. Which approach depends on question 1 in §6.

## 5. Risks and limits

- **Sync window:** chat's window is based on when a file last changed, not capture date. A freshly
  backed-up 2015 photo counts as recent, and a first-time backup could put the whole library in the
  window. The gallery sorts by capture date, so "recent" needs its own definition.
- **Why v1 didn't sync:** to keep the drive out of `syncAll` on wake-ups and avoid a local copy of
  tens of thousands of photo records. Both costs come back, so measure them.
- **Backup interaction:** a device's own backups would come back down through sync. Check this
  doesn't cost much or confuse the ledger.
- **Cache eviction:** iOS and Android can clear cache folders when storage runs low, and old entries
  get evicted. Tiles fall back to the tiny preview and then the server.
- **Web:** its file system is in memory, so this cache won't survive a page reload there.
- **Not yet confirmed:** that tiny previews in `PhotoContent` come back through sync and can be read
  from the local database.

## 6. Open questions

1. What does "synced from the local client" mean: thumbnails downloaded ahead of time by sync, the
   ones the phone made during backup kept locally, or both?
2. How should "recent" be defined for the sync window, given the change-date problem in §5?
3. How big should the photo thumbnail cache be? Suggested start: 1 GB, which holds at least about
   40,000 thumbnails at the 26 KB cap for 320px. A very large library won't fully fit.
4. Which sizes go in the cache? Suggested: pre-download 320px only. Larger sizes load when requested,
   and need a usage limit or a second cache so they don't push out grid thumbnails.
5. Should the Photos drive get live WebSocket updates, or keep the refresh button?

## 7. Task list

- [ ] Answer the open questions in §6
- [ ] Confirm tiny previews come through sync and read back from the local database
- [ ] Measure sync time and database size with a large photo library
- [ ] Videos get a tiny preview in the encrypted `PhotoContent`
- [ ] Mount the Photos drive with a sync policy
- [ ] Gallery reads from the local database
- [ ] Backfill older pages on scroll
- [ ] Separate photo thumbnail cache
- [ ] Put thumbnails on the device ahead of time
- [ ] Test on all four platforms
