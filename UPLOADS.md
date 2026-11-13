# What a user upload looks like

**Status: decided, not built yet.** The shared builder (#911) implements this.

Every mini app lets the user upload something: a photo in chat, a Moment, a Vault document, a Photos backup, a Filesync file. Today each app builds its uploads its own way, so the same photo is named, typed, described and stripped differently depending on where it was uploaded. This document sets one format for everything a user uploads, and the reasons for it. One shared builder in `homebase-upload` produces that format for every app.

Thumbnails and the tiny are specified in [`THUMBNAILS.md`](THUMBNAILS.md). This document says when an upload gets them and where they are stored, not how they are made.

## Contents

1. [Words](#words)
2. [What each app does today](#what-each-app-does-today)
3. [The standard](#the-standard)
4. [Per app](#per-app)
5. [The shared builder](#the-shared-builder)
6. [Migration and older clients](#migration-and-older-clients)
7. [Testing](#testing)
8. [Decisions](#decisions)
9. [Bugs found while writing this](#bugs-found-while-writing-this)

## Words

| Word | Meaning |
|---|---|
| **Upload** | One drive file a user creates: its header (`appData`), its payloads and their thumbnails. |
| **Payload** | One stored part of the upload, under a key such as `chat_web0`. Usually the main file. |
| **Main file** | The payload that holds what the user picked, or the smaller copy we made of it. |
| **Original** | The user's file, byte for byte, as picked. |
| **Final image** | A smaller copy we made (Standard or HD). See `THUMBNAILS.md`. |
| **Descriptor** | A payload's `descriptorContent` string. It travels in the upload manifest, readable by the server. |
| **File content** | The header's `appData.content`. Encrypted on encrypted drives. |
| **Tiny** | The 20 px blurred preview. See `THUMBNAILS.md`. |

## What each app does today

Each app builds its uploads separately. Some differences are deliberate (Filesync must store bytes exactly); most are drift.

| App | Uploader | Content type | File name stored | Original or copy | Metadata (EXIF, GPS) | Tiny |
|---|---|---|---|---|---|---|
| **Chat** photo, video, audio, file | `MessageAttachmentBuilder` → `UploadService` | picked file's MIME, else the extension | files and PDFs: the descriptor *is* the name (raw string). Audio: `AudioFile.name`. Photos and videos: **nowhere** | setting (Standard, HD, Original) | stripped in the encryption step; video by ffmpeg | readable `previewThumbnail` |
| **Moments, feed** posts and comments | `MessageAttachmentBuilder`, then `UploadService` or, for public posts, `enqueuePlaintextUpload` | `file.contentType()`, ignores the picker's type | as chat | always Standard | stripped on encrypted posts only, **not on public ones** | readable |
| **Stickers** (saved) | `StickerService` → `UploadService` | caller's type, HEIC → JPEG | `StickerFileContent.name`, always null | the file | stripped | readable |
| **Photos** backup | `BackupRequestBuilder` → its own outbox path; videos via `UploadService` | platform MIME, can be `""` on Android | `PhotoContent.name`, encrypted, ≤200 code points | the original; videos transcoded to Standard | kept | inside the encrypted content |
| **Vault** | `VaultUploaderService` → `UploadService` | `PlatformFile.contentType()` | `VaultFileContent.name`, **first file only** | the original; videos transcoded to Standard | stripped | readable, on a zero-knowledge drive |
| **Filesync** | own direct upload, no outbox | always `application/octet-stream` | `FilesyncNodeContent.name`, cleaned (NFC, ≤255 bytes) | the original, byte for byte | kept | none |
| **WebDrop** | own outbox path, link key | always `application/octet-stream` | in its encrypted manifest | the original | kept | none |
| **Contact photo** | contacts image API | `PlatformFile.contentType()` | none | the original | kept, GPS included (on the user's own drive) | thrown away |
| **Profile and card photos** | profile attribute API | `image/webp` | none | an 800 px copy | gone (re-encoded) | yes |

**What breaks because of it:**

- Saving a chat photo gives `chat_web0 (6).webp`, because no name was stored (#2393).
- A view can't tell an original from a copy unless chat recorded it, so `THUMBNAILS.md` only lets chat and Moments photos step up to their final image.
- Four descriptor encodings exist: a raw string, a JSON object, a JSON array (link and location previews) and `VideoMetadata`. A video's name is overwritten by its metadata.
- Content type comes from three different places, and Filesync and WebDrop label everything as octet-stream.

## The standard

### 1. One builder

Every user upload, in every app, is assembled by one shared builder (#911). An app only acquires the source: resolves the picker's URI, converts HEIC, crops, renders a PDF page, chooses the quality. The builder turns that into payloads, thumbnails, descriptors and file content, and hands them to `UploadService`.

**Why:** every difference in the table above came from an app re-implementing a step. A step done once can't drift, and a fix (a metadata strip, a name) reaches every app.

### 2. Content type, from the bytes first

The builder decides the content type: detected from the file's bytes first (`ImageFormatDetector` and the video and audio probes), then the platform's MIME type, then the file extension. It's never `""`, and never `application/octet-stream` for a format it recognises.

**Why:** the platform's type is sometimes empty (Android) or wrong (a renamed file), and a wrong type turns a photo into a "file" bubble or freezes a GIF. Bytes don't lie. Filesync and WebDrop may still *store* octet-stream payloads on purpose, but they record the real type, as WebDrop's manifest already does.

### 3. The file name, always, in one field

Every upload records the original file name of every payload, in one field with one meaning, whatever the kind. Rules for the name:

- **The name as the user's device showed it,** never a path. `MessageAttachmentBuilder` falls back to the full local file path for audio today; see [Bugs](#bugs-found-while-writing-this).
- **Cleaned the way Filesync already does** (`FilesyncNames`): NFC, at most 255 UTF-8 bytes, no `/` or NUL.
- **Kept when the content is re-encoded.** A photo sent at Standard keeps `IMG_1234.HEIC` as its name; the extension for saving comes from the saved bytes (#2393).
- **No name when there is none:** a camera capture in the app, a pasted image, a recorded voice note. Then saving uses a dated name.
- **Stickers** keep their neutral name on purpose, so a sticker doesn't leak the sender's camera-roll name.
- **An app's own name is separate.** Where the user renames (a Vault label, a Filesync rename), the app's content holds the current name, and saving prefers it.

**It lives in the payload's descriptor,** which is encrypted whenever the file is: see [Encrypted files encrypt their descriptors](#10-encrypted-files-encrypt-their-descriptors).

### 4. What the main file is, recorded

Every image or video payload records, at upload:

- **whether the main file is the user's original** or a copy we made;
- **the main file's pixel size;**
- **the quality setting** it was sent with.

**Why:** a view must never download an original in place of a thumbnail (`THUMBNAILS.md`, "Showing the images"), and only an upload can know which it is. Today only chat and Moments record this, so Photos, Vault and stickers never step up to a sharper image.

### 5. One descriptor shape

Every payload's descriptor becomes one JSON object, the same for every kind, with the common fields above and a field per kind (an audio's length, a video's codec and duration, a sticker flag, a document's preview text). Readers keep accepting the four old encodings, because existing files are never rewritten.

**Why:** one parser, no overwriting (a video's name survives its metadata), and new fields (a name, a size) added once. Plain files move to the same shape in the same change (decision D): everything that needs a file name works the new way at once.

### 6. Thumbnails and the tiny

- **Every image, video, PDF and sticker upload gets a tiny,** made by the shared pipeline. `THUMBNAILS.md` sets the sizes. Filesync and WebDrop don't, because they store files, not media; contact photos do, instead of throwing theirs away.
- **The quality setting decides the thumbnails and any final image,** passed in by the app (`THUMBNAILS.md`, "Where each caller lands").
- **The tiny is readable, in the header's `previewThumbnail`, for every upload,** encrypted file or not (decision B). At 20 px it can't show anything meaningful; at most it's a vague fingerprint of the image. Lists render it before decrypting anything. Photos keeps reading the encrypted copy in its older backups.

### 7. Metadata: the app decides, the builder applies it

Every upload states whether the main file keeps or strips its metadata (location, camera, capture time). The app decides, as `THUMBNAILS.md` already says, and the builder applies it. It no longer happens in the encryption step, which public uploads skip.

| App | Default | Why |
|---|---|---|
| Chat, Moments, feed, stickers | strip | Shared with others; location must never leak by accident. Moments' GPS opt-in stays. |
| Photos | keep | The user's own library; capture time and place are the point. |
| Filesync, WebDrop | keep | They move files; changing the bytes breaks the sync and the transfer. |
| Vault | keep | The user's own encrypted storage, like Photos (decision C). It strips today; that changes with the builder. |
| Contact photo | strip | Nobody needs a contact photo's location. It stays on the user's own drive today, and a shared contact card already goes through chat's strip. |
| Profile and card photos | strip | Public. Already re-encoded. |

### 8. Video

- **The quality comes from the app's setting.** Photos backup and Vault transcode to Standard today because nothing sets one (#2386).
- **The original video's name and the recorded fields survive.** The video's metadata is one field of the descriptor, not the whole of it.

### 9. Payload keys

Existing keys stay as they are (`chat_web0`, `mmt_0000`, `photo_main`, `vlt_pg_00`, `sync_data`), because receivers look payloads up by key. New apps use `<app>_<index>`, and the builder names them.


### 10. Encrypted files encrypt their descriptors

**Decided (Michael, 2026-11-13): a payload's descriptor is encrypted exactly when its bytes are.** An encrypted file's descriptors are encrypted with the file's own AES key, so whoever can read the file can read them and nobody else can. A public post's descriptors stay plain, like its photo.

**Why the whole descriptor, not just the name.** Descriptors hold more than names. Today they carry a location message's coordinates and address, a shared link's URL, title and description, a Vault note's preview text, and a video's details, all readable on the server even in an encrypted chat. Encrypting only the name would leave those readable, and every field added later would need its own "secret or not" decision. Encrypting the whole descriptor protects everything by default.

**The format** is the one both clients already use for encrypted query strings and socket messages (`SharedSecretEncryptedPayload`):

```json
{"iv": "<base64, 16 bytes>", "data": "<base64, AES-CBC of the descriptor>"}
```

- **The key** is the file's AES key, the one its payloads use.
- **The IV is fresh and random for each descriptor,** never the payload's own IV: the payload's bytes are encrypted with that key and IV, and reusing the pair leaks.
- **Telling old from new:** an old descriptor is never a JSON object holding exactly `iv` and `data`, so readers need no extra marker.
- **Size:** the server allows 1,024 characters per descriptor. Encryption leaves about 730 for the descriptor itself; anything bigger goes, as today, into its own encrypted payload (`pld_desc`).

**The server needs no change.** odin-core stores and copies descriptors and checks their length, and never reads them.

**The web apps do read them** (odin-js): `community-app` (chat and Community: file names, video duration, link previews, and it writes `file.name` as a photo's descriptor), the feed (`common-app`: download names, gallery details), WebDrop and the video player (`ui-lib`). So both clients change together:

1. **Readers and writers, in chat-kmp and odin-js, in the same release:** decrypt an `{iv, data}` descriptor with the file's key and keep reading plain ones; encrypt every descriptor of an encrypted file.
2. **Existing files stay as they are.**

## Per app

What each app sends to the builder, and what it keeps for itself:

| App | Source it acquires | Quality | Metadata | Name | Stays its own |
|---|---|---|---|---|---|
| Chat | picker, camera, paste, share sheet | user setting | strip | picked name | sending, recipients, view-once |
| Moments, feed | as chat | user setting (Standard today) | strip, GPS opt-in | picked name | public posts' plaintext upload |
| Stickers | cut-out, saved image | Original rows | strip | none (neutral) | — |
| Photos | device library export | Original (photos), app setting (videos) | keep | `DISPLAY_NAME` / `originalFilename` | hashing, dedupe, `uniqueId` |
| Vault | picker, scanner, PDF render | Original | keep | picked name, every file | labels, notes, sections |
| Filesync | picker, folders | none (stores bytes) | keep | picked name, cleaned | sync, versions, peer writes |
| WebDrop | picker | none | keep | picked name | link key, manifest |
| Contact, profile photos | picker, crop | their own sizes | strip | none | their own APIs |

Filesync, WebDrop and the profile and contact APIs don't go through `UploadService`. They use the builder for the parts that apply (content type, name, tiny, metadata), and keep their own transport.

## The shared builder

In `homebase-upload`, working name `UserUploadBuilder`:

```text
build(sources, app options) → payloads + thumbnails + descriptors + file-content fields
  sources:      per payload: bytes or path, picked name, platform MIME
  app options:  quality, keep or strip metadata, payload key scheme, sticker, where the tiny lives
```

- It calls the thumbnail pipeline (`createMediaImages`, `planMediaImages`) for images, the video processor for videos, and the PDF renderer's result for documents.
- It returns what each app needs to put in its own file content (the name, for apps that keep it there), so apps stop building descriptors themselves.
- `MessageAttachmentBuilder`, `MessageThumbnailGenerator`, `FinalImagePayload` and the per-app copies of the same steps fold into it.

## Migration and older clients

**One switch, at once** (decision D). The new descriptor shape, the recorded names and the encrypted descriptors ship together, for every app and every kind of payload, in chat-kmp and odin-js in step:

- **Readers and writers in the same release.** Everything that needs a file name works the new way from that release on.
- **Existing files are never rewritten.** Readers keep reading every old encoding (raw-string names, `ImageFile`, `AudioFile`, `VideoMetadata`, the link and location arrays, plain descriptors on encrypted files).
- **The cost, accepted:** an app that hasn't updated shows a new upload's descriptor as text (or ciphertext) until it does. People update quickly.

## Testing

- **One test per app** that builds a typical upload and checks the standard: content type, name, original marker, size, quality, tiny, metadata kept or stripped.
- **A guard** (Konsist): no code outside the builder writes a descriptor or calls the thumbnail pipeline for an upload.
- **Reader tests** for every old encoding, so nothing already stored stops rendering.
- **The metadata rule on the real platforms,** because stripping is per format and per platform: a GPS-tagged JPEG, HEIC and PNG, uploaded by each app, checked for location afterwards.

## Decisions

All decided by Michael on 2026-11-13:

| | Decision | Outcome |
|---|---|---|
| **A** | Where the file name lives | In the descriptor, and every descriptor of an encrypted file is encrypted with the file's key, in both clients. See [section 10](#10-encrypted-files-encrypt-their-descriptors). |
| **B** | Whether the tiny is encrypted on encrypted files | No. It stays readable: at 20 px it can't leak anything meaningful. |
| **C** | Whether Vault keeps metadata | Always keeps it, like Photos. |
| **D** | When the new descriptor replaces the old forms | At once, for every kind, plain files included. Unencrypted files' descriptors stay plain. |

## Bugs found while writing this

Bugs in today's code. The first is filed on its own; the builder fixes the others:

1. **Public feed posts and comments publish location** (#2395, fixed right after the builder lands). They upload through `enqueuePlaintextUpload`, which skips the encryption step, and only that step strips metadata. An Original photo, or a Standard one whose file is kept, is published with its GPS.
2. **A voice note or audio file can be named by its full local path** (`MessageAttachmentBuilder`, `attachment.displayName ?: attachment.filePath`), which puts a device path in the descriptor. Covered by #911's name rule.
3. **Vault keeps only the first file's name** when several are picked (`VaultViewModel`). Covered by #911's migration of Vault.
4. **Saving a chat photo to Vault names it with the date,** not the file name (`ChatMediaFullScreenHost`). Covered by #2393's save-name helper, once #911 records names.
