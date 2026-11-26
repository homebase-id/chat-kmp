# Shared photo albums: plan (Filesync method)

Status: plan; phase 0 done (findings below). Chosen approach: option A from `PHOTO_ALBUM_SHARING.md`. Everything lives on the album
owner's Photos drive, viewers read it through share rows, and people allowed to add photos upload into the owner's
drive the way Filesync members write into a shared folder.

## What Filesync already does, and what we copy

| Filesync piece | What it does | Photos counterpart |
|---|---|---|
| `FilesyncSession` | Signs in as the Filesync app: only the app owning the drive and circle may write circles and shares | `PhotobaseSession`, signed in as Photobase |
| `FilesyncSharing.share` | Creates or reuses the audience's circle, enrolls members, seals the circle key, writes one `ShareRequest` per file | `PhotoAlbumSharing.share(album, audience, access)` |
| `shareRequest` | Locks the file's AES key with the circle's key (`ShareKeys.lockFileKey`) | Same, on the Photos drive |
| `sealCircleKey`, `sealPending` | Seals the circle key to each member's drive key; members without the drive wait | Same, against each member's Photos drive key |
| Recipients circle (`filesync`) | The app circle every recipient joins; it grants the drive's read-level permissions | The declared `photobase` circle, granted Read+React+Comment |
| Editors circle (`filesync-edit`) and `syncEditors` | Write on the drive for exactly the people with a live Edit share | `photobase-edit`, kept to people with a live Add share on an album |
| `inherit` | A new file in a shared folder gets the folder's share rows | A photo added to a shared album gets the album record's rows |
| `sharers`, `sharedBy(owner)` | Lists who shares with you; queries their drive over peer | "Shared with me" albums; query the owner's Photos drive over peer |
| `FilesyncMemberWrites.add` | A member's new file: sealed to the owner's drive key, given a global transit id, sent with its share rows already attached (odin-core `SelectiveShareTests.MemberWrites`) | A member adding a photo to someone's album |
| `FilesyncShareUpkeep` | On each reconnect: seals pending keys, takes Write from ended Edit shares | `PhotoShareUpkeep` |
| `addedBy` (`FilesyncNode.kt:173`) | Marks a file someone else added | Marks a photo a member added |

## Access levels

- **View:** Read+React+Comment on the album's share rows.
- **Add:** View, plus membership in `photobase-edit`, which grants Write on the owner's Photos drive.

## Phases

### 0. Server checks (before building)
1. What drive Write lets a `photobase-edit` member do: only deposit new files, or also update or delete files the owner
   never shared with them? `FilesyncMemberWrites.replace` shows Write can update a *shared* file by global transit id.
   If it can reach unshared files too, stop and switch to option B.
2. Whether a guest can post a comment and a reaction over peer on a shared photo with Comment and React on its row.
3. What happens when a member deposits a photo whose content-derived uniqueId (`PhotoUniqueId`) already exists on the
   owner's drive: refused, merged, or a duplicate?

#### Phase 0 findings

Read from odin-core (paths under `src/services/Odin.Services/` and
`tests/apps/Odin.Hosting.Tests.V2/ZeroKnowledge/`). The Photos drive is a Selective Share drive
(`AppConfig.kt`, `photosDrive.sharingModel`), so the share-row rules below apply to it.

1. **Drive Write only adds files. Option A stands.** On a Selective Share drive the share rows, not the circle
   grant, decide what a member may do to a stored file. Every header write and every hard delete asks
   `AssertMemberMayWriteAsync` (`Drives/FileSystem/Base/DriveStorageServiceBase.cs:2138-2177`, hard delete at
   `:997-1000`, overwrite at `:1116-1119`). A stored file can be changed by its original sender while it has no
   share (`SenderMayChangeAsync`, `:2578-2581`), or otherwise only through a live share row with `Write` on that
   file (`AssertSenderOrShareMayWriteAsync`, `:2197-2208`). `DriveAclAuthorizationService.CallerMayAsync`
   (`Authorization/Acl/DriveAclAuthorizationService.cs:110-130`) needs both the drive bit and a row granting it.
   Over peer, a delete goes through the same check (`Peer/Incoming/Drive/Transfer/SentFileChanges.cs:29-57`).
   A new file needs drive Write alone, and it stays owner-only unless it carries the writer's share rows
   (`DriveStorageServiceBase.cs:2175-2176`). Tests that pin this: `AMemberWithoutWriteCantEdit` and
   `AMemberDeletesASharedFileOnlyWithWrite` (`SelectiveShareTests.cs:440-476`),
   `AMemberOnTheOwnersRoutesChangesASharedFileOnlyWithWrite` (`:520-566`, which also covers hard delete and the
   YouAuth path), `AFileIsAddedWithDriveWriteAlone` (`:495-518`), and `AFileNoShareNamesAMemberOnIsMissingNotATombstone`
   (`:590-600`). So a `photobase-edit` member can deposit photos and can't change or delete a photo unless a row
   gives them Write. **Consequence:** an Add share's rows must not carry `Write`. If they did, every Add member
   could change or delete every photo in the album. Add rows carry Read+React+Comment, the same as View. Add is
   only membership in `photobase-edit`.
2. **Yes, both work over peer. Today's registration blocks them.** A share row narrows its circle's grant: the
   member needs the bit on the row *and* on the drive through their circle (`DriveAclAuthorizationService.cs:123-129`;
   `AShareGrantsOnlyWhatItsCircleGrantsTheDriveAtTheTime`, `SelectiveShareTests.cs:619-643`). Reaction: the
   peer `AddReaction` by global transit id works with React on the row (`AMemberReactsOnlyWithAShareWithReact`,
   `:602-617`). Comment: a `Comment` file system upload to the transient drive, sent to the owner, with
   `ReferencedFile` and `AppData.GroupId` both set to the photo's global transit id, encrypted with the photo's
   file key under a `FileKey` header that carries only the comment's IV (`AssertMayWriteSharedCommentAsync`,
   `DriveStorageServiceBase.cs:2214-2236`; `AMembersCommentIsEncryptedWithTheFilesKeyAndReadWithTheirShare` and
   `AMembersCommentIsRefused`, `SelectiveShareTests.cs:718-780`). Only a comment's author can edit or delete it
   (`OnlyACommentsAuthorDeletesIt`, `:783`). The `photobase` circle currently grants Read only, so phase 1
   has to add React and Comment.
3. **Refused, silently. No merge, no duplicate.** An incoming peer file is matched first by global transit id,
   then by `UniqueId` (`Peer/Incoming/Drive/Transfer/PeerFileWriter.cs:262-266`). A match makes the deposit a
   "send again" of the stored file. The member isn't that file's original sender, so it's refused
   (`SentFileChanges.cs:57`, "Sender does not match original sender"). A deposit that matches a tombstone is
   refused too (`:32-35`), and so are share rows sent with a send again (`PeerFileWriter.cs:283-287`). The
   inbox logs the refusal and marks the item complete (`PeerInboxProcessor.cs:307-324`). The member's outbox
   has already reported it delivered, so the member is never told. **Consequence:** the member's client can't
   rely on delivery. It checks over peer that the photo arrived (by global transit id, through the album's
   rows) before it reports the add as done. A photo the owner already has stays out of the album until the
   owner adds it.

### 1. Photobase session and registration
- `PhotobaseSession`, like `FilesyncSession`: sign in as the Photobase app, restore on start, sign out at logout.
- `photobaseAppRegistration`: the `photobase` circle grants Read+React+Comment. Existing installs re-register through
  the existing install and extend flow. Update `PhotobaseAppRegistrationTest`.
- The editors circle is created on first Add share, as Filesync creates `filesync-edit`.

### 2. Album membership the server can see
- An album's id goes onto its photos as an **app tag** as well as the local tag, so a guest's peer query can find them.
  `PhotoMover` writes both. The gallery keeps reading local tags.
- Existing albums are migrated once: tag every photo carrying an album's local tag.
- `PHOTOS_SYNC.md` and the comment at `PhotobaseConfigFile.kt:151` change: a shared album's record is meant to be read.

### 3. Owner: sharing an album
- `PhotoAlbumSharing` (homebase-photobase), modelled on `FilesyncSharing`:
  - `share(album, audience, access, expiresAt)`: circle, enroll, seal, then share rows for the album record and
    every photo with the album's tag, in chunks of `MAX_SHARES_PER_REQUEST`.
  - `withdraw(album, audience)`, `setMembers`, `sharedWith(album)`.
  - Adding a photo to a shared album writes its rows (`inherit`); removing it withdraws them. Deleting the album
    withdraws everything first.
- `PhotoShareUpkeep`: `sealPending`, editor sync and expiry, on each reconnect.
- **Invite:** a chat message carrying the owner and the album id, like Filesync's `FileShareDescriptor`.

### 4. Guest: viewing a shared album
- "Shared with me" on the Library screen: one section per sharer, then their albums.
- An album view reads over peer: the album record, then the photos with its app tag, paged from the owner's drive.
  Each photo's key comes from its row's `lockedFileKey`, opened with the circle key.
- First version keeps the results in memory. Mirroring them into the local index is a later step.
- The photo viewer, comments sheet and reactions work on these the same as on your own photos.

### 5. Guest: adding photos (Add access)
- `PhotoMemberWrites`, modelled on `FilesyncMemberWrites.add`:
  - Payloads and thumbnails built by the Photos backup builder (`BackupRequestBuilder` on `UserUploadBuilder`), so no
    second builder. Per `UPLOADS.md`, the member's app's `MetadataPolicy` decides location stripping.
  - Sealed to the owner's Photos drive key, a global transit id picked on the device, the album's app tag set, and the
    album record's share rows attached so every member sees it at once.
  - Tracked like `FilesyncPeerSends`: delivered, retrying, refused, not confirmed.
- The photo lands in the owner's library and backup as well as the album, marked as added by the member.

### 6. Comments and reactions
- Switch PR https://github.com/homebase-id/chat-kmp/pull/2694 comments from moments-style `groupId` files to the
  feed and Filesync model: `Comment` file system with `referencedFile` pointing at the photo, stored on the photo
  owner's drive. A guest's comment goes over peer.
- Reactions stay on the server's group-reactions API; a guest's goes over peer.
- `PhotoAudience` goes away: the share rows decide who sees what.

## Risks
- **Drive-wide Write.** An editor can deposit files anywhere on the owner's Photos drive and fill their storage.
  Mitigation: the editors circle holds only people with a live Add share; Add is off by default; expiry ends it.
- **Member photos count against the owner's storage.**
- **A member can't take back a photo once added;** only the owner can remove it.

## Files

- New: `PhotobaseSession`, `PhotoAlbumSharing`, `PhotoShareUpkeep`, `PhotoMemberWrites`, shared-album screens.
- Changed: `AppConfig.kt` (registration), `PhotoMover` (app tag), `PhotoAlbums`, `PhotoCommentsService` and
  `PhotoActionService` (comment model), `AppModule.kt`, `PHOTOS_SYNC.md`, `UPLOADS.md` if member uploads need a section.
