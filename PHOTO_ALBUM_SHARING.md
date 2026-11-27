# Shared photo albums: planning

Status: proposal, undecided. Two options for letting other identities see and add to a Photos album.

## Where things stand

- Albums are owner-only today: an album is a `PhotobaseRecord` (fileType 7203) and its photos carry its id as a
  **local tag**, which no one else can see.
- Every photo and album record is ACL Owner, `allowDistribution = false`, and the Photos circle has Read only.
- The Photos drive is SelectiveShare, the same sharing model Filesync uses, so Filesync's share machinery applies.
- Draft PR https://github.com/homebase-id/chat-kmp/pull/2694 adds comments and reactions on photos in the moments
  style (comment files linked by `groupId`, sent to an audience that is empty for now).

## Option A: contributors upload into the owner's drive (Filesync's Edit sharing)

The album and all its photos live on the album owner's Photos drive. Viewers get read access through share rows, one
per photo per circle, with each photo's key locked to the album's circle. People allowed to add photos join an
"editors" circle that has Write on the owner's Photos drive, and their uploads land there.

Pros:
- Simplest to build: reuses Filesync's circles, share rows, key sealing and peer reads.
- The owner ends up holding every photo, and they're included in the owner's backup.
- One drive to query, so paging, sorting and loading are simple and fast.
- Comments and reactions are all on one drive.
- The owner can delete any photo in the album.

Cons:
- Write covers the whole drive, not just the album. Unless the server blocks it, editors can add files anywhere,
  including the owner's backed-up library. The Filesync client has no guard against this; it only limits who is in
  the editors circle and marks who added each file.
- Still to confirm on the server: whether Write also lets editors change or delete the owner's existing photos. If it
  does, this option is ruled out for a backup drive.
- Contributors' uploads count against the owner's storage.
- Contributors lose control of a photo once they've uploaded it.

## Option B: each contributor keeps their photos and shares them in

The owner's album record holds the album id and the member list. Each member tags their own photos with the album id,
keeps them on their own Photos drive, and shares them with a circle of their own that mirrors the owner's member list.
The album view queries the owner's drive and every member's drive over peer and merges the results. An optional
"Save to my library" copies a shared photo into your own backup.

Pros:
- Nobody ever has Write on someone else's drive, so backups can't be touched.
- Each person keeps owning their photos, and their own storage pays for them.
- Uses only read-sharing, which viewing needs anyway.
- "Save to my library" lets you keep the photos you care about.

Cons:
- The most work: one circle per contributor kept in step with the owner's member list, plus merging queries across
  several drives (paging and sort order across drives is non-trivial).
- More fragile: a member's photos disappear while their server is offline.
- A member who leaves takes their photos with them unless others saved copies.
- The owner can only hide another member's photo, not delete it.
- Comments and reactions are spread across several drives, which makes counts and threads harder.

## Differences at a glance

| | A: upload to owner | B: each keeps their own |
|---|---|---|
| Trust | Editors get Write on the owner's whole drive | Nobody writes to anyone else's drive |
| Ownership | Photo becomes the owner's; stays if the contributor leaves | Stays the contributor's; leaves with them unless saved |
| Storage | Owner's | Contributor's |
| Moderation | Owner can delete any photo | Owner can only hide others' photos |
| Circles and keys | One circle, one key | One circle per contributor, mirrored from the owner's list |
| Reading | One query on one drive | One query per member drive, merged |
| Availability | Owner's server | Every member's server |
| Comments and reactions | All on the owner's drive | On whichever drive holds the photo |
| Backup | Contributions are in the owner's backup | Only saved copies are |

## How sharing works technically (both options)

Based on Filesync's sharing (`FilesyncSharing.kt`, `DriveShareProvider.kt`). Key handling is outlined from call
names, not verified line by line.

Owner:
1. **Sign in as Photobase.** Get a Photobase app session (like `FilesyncSession`): only the app that owns the drive
   and the circle may write circles and share rows.
2. **Create the album's circle.** `CreateAppCircleRequest` with the members. People not yet connected wait as
   pending members.
3. **Seal the circle's key to each member.** Fetch the member's Photos drive public key over peer
   (`peer.drivePublicKey(member, "photobase", "photos")`), encrypt the circle key to it, and store it through
   `CircleShareKeyProvider`. A member without a Photos drive waits until they set one up.
4. **Share the files.** `DriveShareProvider.share`: one `ShareRequest` per file (the album record and each photo)
   with the circle id, the permissions (Read+React+Comment) and a `lockedFileKey`, which is that file's AES key
   encrypted with the circle's key.
5. **Invite.** A chat message carrying the owner's identity and the album id (like Filesync's `FileShareDescriptor`).

Member:
1. **Get the circle's key.** Decrypt the sealed circle key with their own drive's private key.
2. **Query the owner's drive over peer.** `PeerDriveQueryProvider` for the album record and the photos tagged with the
   album id. The owner's server returns only files with a share row for a circle they're in; that check is what
   controls access.
3. **Open the files.** The circle key opens each file's `lockedFileKey`; photos download over peer and are decrypted
   on the device.
4. **Option A:** upload new photos to the owner's drive through the editors circle's Write.
   **Option B:** repeat the owner's steps 2–4 on their own identity, with their own circle and their own photos tagged
   with the album id.

What makes it secure: the owner's server decides who sees which file (circle membership plus share rows), and the
keys decide who can decrypt it (only circle members can open the circle key).

## Lining albums up across identities (option B)

- The owner's album record is the single source of truth: its id and its member list.
- A member who joins saves a local "joined album" record pointing at the owner's identity and the album id, and
  creates their own circle copied from the owner's member list.
- When the owner adds or removes someone, each member's app reads the new list and updates its own circle.
- The album shows its members from the owner's record. A member counts as joined once their own share exists.

## "Save to my library" (option B)

Download the shared photo over peer, decrypt it, and back it up to your own Photos drive as your own photo.
- A photo's unique id comes from its contents, so one you already have dedupes instead of being stored twice.
- The album view must show only one of your copy and the contributor's.
- The upload has to follow `UPLOADS.md`: through `UserUploadBuilder`, or reusing the original's payloads and
  thumbnails as they are, with location stripped or kept per the Photos app's `MetadataPolicy`.

## Changes needed either way

- Album membership moves from local tags to something guests can query, such as an app tag on the photo.
- The Photos circle (or the album circles) grant React and Comment.
- Guests' comments and reactions land on the photo owner's drive, so PR #2694's moments-style comments move to the
  feed/Filesync comment model (`Comment` file system, `referencedFile`). Its reaction code still fits.
- `PHOTOS_SYNC.md` ("Sharing UI is out of scope") and the comment at `PhotobaseConfigFile.kt:151` change.

## Open questions

1. What does drive Write let a circle member do on a SelectiveShare drive: only add files, or also change and delete
   existing ones? (odin-core) This decides between A and B.
2. Can a guest post a comment on a photo through a share row with Comment permission? Filesync's circle grants it,
   but its client hasn't been checked for posting comments.
3. Does an editor's upload over peer (option A) need anything beyond the editors circle, such as the owner's drive
   key for encryption?

## Recommendation

Answer open question 1 first. If an editor can only add files, A is the better product and the cheaper build. If
Write allows changing or deleting existing photos, go with B.
