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
  - Ignore keys you don't know. If `v` > 1, show the item but don't change it.

## Listing a folder
- One `query-batch` with `groupId: [<folder's node id>]`, `fileType: [7101, 7102]` and
  `fileState: [active]`, in pages.
- Every row is encrypted: names, sizes and times. Decrypt each one, then sort on the device.
- Skip any row that won't decrypt, isn't encrypted or has a different file type; a name that
  breaks the name rules under "Uploading"; and a file without `size` or without a hashed
  `sync_data` payload.
- odin-core has no index on `groupId` yet, so on a big drive each listing scans the whole drive
  (to fix in odin-core).

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
- A retry that gets 4105 (`ExistingFileWithUniqueId`) means the first create already succeeded.
