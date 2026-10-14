# Filesync drive: browsing the files on demand

Homebase Filesync (homebase-id/filesync) syncs folders on desktop computers with the user's
Homebase. A mobile browser can list and open those files without syncing them: it shows one
folder at a time and downloads a file only when it's opened. Full details:
filesync `docs/spec/node-schema-v1.md` and `docs/spec/encryption.md`. The schema doc is kept
current: read its "Changes" list before touching the mini-app.

## The drive
- Find it by slug: `GET /api/v2/apps/filesync/drives/filesync` (the drive type GUID
  `5da48122-e946-487a-8b9d-589642f3c91d` is provisional, so don't hard-code it).
- The app needs a read grant on the drive (write too, for uploading).

## Nodes
Every file and folder is one Homebase file:

| Field | Folder | File |
|---|---|---|
| `fileType` | `7101` | `7102` (both provisional: keep them in one constant) |
| `uniqueId` | node id | node id |
| `groupId` | parent's node id | parent's node id |
| payload | none | one, key `sync_data` |

- **Root id:** `2a1c0d55-8ad3-417e-a5d7-42c3d0baba0e`, the same on every drive. The root is
  never stored. An item with `groupId` = root id is at the top level.
- **Encrypted content (`appData.content`):**
  `{"v":1, "name":"Report.pdf", "mtime":1759920000123, "size":52311, "x":true, "ct":1451606400123}`.
  - `mtime` and `ct` (creation time, optional, files and folders) are in Unix milliseconds;
    `size` is the plaintext size; `x` means executable and is left out when false. Folders have
    `v`, `name` and maybe `ct`.
  - `tags` holds Finder tags, `[{"n": "Red", "c": 6}]` (`c` 1 grey … 7 orange, optional). The mini-app shows them as dots and
    chips and filters on them, and writes them back as read.
  - Ignore keys you don't know. If `v` > 1, show the item but don't change it.

## Listing a folder
- One `query-batch` with `groupId: [<folder's node id>]`, `fileType: [7101, 7102]`,
  `fileState: [active]` and `archivalStatus: [0]`, in pages. A deleted node is only removed:
  `archivalStatus` 2 with a content key `rm`, still in its folder (filesync's "Recently deleted").
- Every row is encrypted: names, sizes and times. Decrypt each one, then sort on the device.
- Skip any row that won't decrypt, isn't encrypted or has a different file type; a name that
  breaks the name rules under "Uploading"; and a file without `size` or without a hashed
  `sync_data` payload.
- odin-core has no index on `groupId` yet, so on a big drive each listing scans the whole drive
  (to fix in odin-core).

## What the list shows
Everything comes from the node's HomebaseFile; nothing is stored beside it.
- Kind and icon come from the name's extension: the payload's `contentType` is always `application/octet-stream`.
- Size is `content.size`; modified is `mtime` (files only); created is `ct` when set; `x` shows a "runs" badge.
- "On Homebase since" is the header's `created`; "Last uploaded" is `sync_data`'s `lastModified`; the info pane names the
  payload's hash algorithm.
- There are no thumbnails, and folders have no size or item count.

## Downloading
- Fetch payload `sync_data` and decrypt it with the file's key (AES-CBC, the same as other
  encrypted payloads).
- Range reads work: start one block before the range, for videos and resuming.
- Check the hash: the payload descriptor's `hash.contentHash` is the plaintext hash, encrypted
  with the file key and IV = payload IV XOR `0xAA…AA`. `hash.algorithm` is `blake3` (written by
  Filesync) or `sha256`: verify with the one it names. Refuse a file with an unknown algorithm,
  no hash, or a hash that doesn't match.

## Uploading (new file or folder)
- `uniqueId` = a new random GUID, `groupId` = the parent folder's node id, and always
  `isEncrypted: true` with a new random key.
- Files need a `sync_data` payload (`application/octet-stream`) with a random IV and both hashes:
  - `storedHash` over the ciphertext;
  - `contentHash` over the plaintext, encrypted as above;
  - Write BLAKE3. SHA-256 is accepted, but it costs Filesync an extra pass per download and can
    cause needless conflict copies. A file without both hashes is ignored by Filesync.
- Content: `v`, `name`, and for a file `mtime` and `size`. Send `ct` only when the platform knows
  the real creation time (a photo's capture date), never the upload time.
- Names: Unicode NFC, at most 255 UTF-8 bytes, no `/` or NUL, not `.` or `..`.
  - Unique within the folder, ignoring case and Unicode form (as on macOS and Windows): add
    `(1)`, `(2)` and so on if needed.
- Rename or move: a header-only update of `content.name` and/or `groupId`, guarded by the
  `versionTag`. Any update decodes the content it read as a JSON object, changes only its own
  keys and writes the rest back, unknown keys included: rebuilding it from `FilesyncNodeContent`
  would drop `ct`, `x` and later keys for every device.
- Delete: delete a folder's contents from the bottom up, then the folder. Desktop clients move
  local copies they lose to `.hbsync/trash/`, so a deletion from the phone can be recovered there.
- A retry keeps the node id, including one the user asks for after a failure, so a retry that gets 4105
  (`ExistingFileWithUniqueId`) means an earlier attempt already succeeded.

## Recently deleted
filesync's own rules are in its node schema ("Recently deleted") and `deleted.rs`; the mini-app follows them exactly.
- **Removed:** `archivalStatus` 2. Its `rm: {at, root}` says when, and which delete took it along (itself, or a
  folder it was in). With status 2 and no readable `rm`, it was removed at the header's `updated`, on its own.
  Any other status is live, even with an `rm` left behind.
- **Lists:** the whole drive with `archivalStatus: [2]`. The topmost items are the ones whose parent isn't
  removed too; each counts the items below it with the same `root`.
- **Restore** is a metadata-only update by fileId:
  - It uses the node's own key with a new IV, its `versionTag`, an empty manifest (the payload stays) and
    `archivalStatus` 0.
  - The content is written back as read, unknown keys included, minus `rm`.
  - Order: the removed folders above the item first, outermost first, then the item, then what its delete
    took along.
  - An item whose folder is gone for good goes to the top level; one whose folder didn't come back stays
    removed.
  - A name a live sibling took meanwhile gets ` (restored)`, then ` (restored 2)` up to 99, before the
    extension.
  - A `VersionTagMismatch` or not-found skips the item: another device changed it.
- **Delete forever:** only what the user saw.
  - Re-read each item first, and leave it if its `versionTag` changed. The batch delete itself has no
    version guard, so a change in the moment between the re-read and the delete isn't caught.
  - Delete files before folders (`delete-batch/by-file-id`), which leaves tombstones.
  - The 30-day purge is the desktops' job; the phone never purges on its own.
- **An item with `v` > 1** is listed, but restore and delete forever leave it as it is.
- **Every write sends `archivalStatus`:** the server replaces the whole header and takes a missing one for 0.
