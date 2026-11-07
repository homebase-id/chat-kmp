# Photobase gallery: local-first loading — plan

**Status: proposed, nothing built.** Replaces the "Gallery data" decision in `PHOTO_BACKUP_PLAN.md`
§3.5 once signed off, along with the open questions in §5.

## 1. Goal

- The first thing every tile shows is the tiny preview embedded in the file metadata.
- Recent thumbnails come from the device, not the server.
- The server is only asked for thumbnails when scrolling back in time.

## 2. How the gallery loads

1. **Opening:** the gallery reads photo records from the local database, so tiles appear with their
   tiny previews without waiting for the network, including offline.
2. **Sharp thumbnails:** each tile swaps its tiny preview for a thumbnail stored on the device.
3. **Scrolling back:** past what's on the device, the gallery fetches older records and thumbnails
   from the server and saves them locally.

Photos and videos behave the same way.

## 3. How it's built

### 3.1 Sync the Photos drive

Follows the chat pattern:

- **First sync:** a `DriveSyncPolicy` pulls a recent window of records.
- **Later syncs:** a saved cursor picks up every change, including deletes.

### 3.2 Fetch older pages on scroll

A backfill modelled on `ChatServerHistory`, triggered by scrolling. Fetched pages are saved locally
with `cursor = null`, so the sync cursor doesn't move.

### 3.3 Tiny previews for every file

Photos and videos both carry their tiny preview in the encrypted `PhotoContent`, so the drive stays
zero knowledge. For videos, the metadata is built after video processing, which is what makes the
preview. Videos backed up without one need a decision: fix them up, or wait for the sharp thumbnail.

### 3.4 A separate thumbnail cache

Photos drive thumbnails are encrypted and read the same way as chat's, but live in their own disk
cache, so a photo library can't push out chat images.

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
  `thumbCacheFor`, and `evictFile` and `readThumbThrough` too if they turn out to need it.
- **Housekeeping:** it's included in `clearCaches`, `getCacheStats` and `CacheAudit`'s directory list.
- **Layering:** `homebase-api` can't depend on Photobase, so `AppModule` passes in the drive ID and size.

### 3.5 Thumbnails on the device ahead of time

Recent thumbnails are on the device before the gallery asks for them, with their own small download
limit so they never hold up chat images. How they get there depends on question 1 in §5.

## 4. Risks and limits

- **Sync window:** a window based on when a file last changed treats a freshly backed-up 2015 photo
  as recent, and a first-time backup could put the whole library in it. The gallery sorts by capture
  date, so "recent" needs its own definition.
- **Sync cost:** the drive joins `syncAll` on wake-ups, and the device holds up to tens of thousands
  of photo records. Measure both.
- **Backup interaction:** a device's own backups come back down through sync. This must not cost much
  or confuse the ledger.
- **Cache eviction:** iOS and Android can clear cache folders when storage runs low, and old entries
  get evicted. Tiles fall back to the tiny preview and then the server.
- **Web:** its file system is in memory, so this cache won't survive a page reload there.
- **Not yet confirmed:** that tiny previews in `PhotoContent` come back through sync and can be read
  from the local database.

## 5. Open questions

1. What does "synced from the local client" mean: thumbnails downloaded ahead of time by sync, the
   ones the phone made during backup kept locally, or both?
2. How should "recent" be defined for the sync window?
3. How big should the photo thumbnail cache be? Suggested start: 1 GB, which holds at least about
   40,000 thumbnails at the 26 KB cap for 320px. A very large library won't fully fit.
4. Which sizes go in the cache? Suggested: pre-download 320px only. Larger sizes load when requested,
   and need a usage limit or a second cache so they don't push out grid thumbnails.
5. Should the gallery update live over the WebSocket, or from a refresh button?

## 6. Task list

- [ ] Answer the open questions in §5
- [ ] Confirm tiny previews come through sync and read back from the local database
- [ ] Measure sync time and database size with a large photo library
- [ ] Tiny previews for videos in the encrypted `PhotoContent`
- [ ] Sync the Photos drive
- [ ] Gallery reads from the local database
- [ ] Backfill older pages on scroll
- [ ] Separate photo thumbnail cache
- [ ] Thumbnails on the device ahead of time
- [ ] Test on all four platforms
