# What a user upload looks like

**Status: draft for discussion.** Nothing here is built yet. The decisions marked **Open** need an answer before the shared builder is written (#911).

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
8. [Open decisions](#open-decisions)
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

**Where it lives is Open (decision A).** The descriptor is readable by the server, which is fine for ordinary drives but not for zero-knowledge ones (Vault, Photos). Photos already puts its names in encrypted file content for that reason.

### 4. What the main file is, recorded

Every image or video payload records, at upload:

- **whether the main file is the user's original** or a copy we made;
- **the main file's pixel size;**
- **the quality setting** it was sent with.

**Why:** a view must never download an original in place of a thumbnail (`THUMBNAILS.md`, "Showing the images"), and only an upload can know which it is. Today only chat and Moments record this, so Photos, Vault and stickers never step up to a sharper image.

### 5. One descriptor shape

Every payload's descriptor becomes one JSON object, the same for every kind, with the common fields above and a field per kind (an audio's length, a video's codec and duration, a sticker flag, a document's preview text). Readers keep accepting the four old encodings, because existing files are never rewritten.

**Why:** one parser, no overwriting (a video's name survives its metadata), and new fields (a name, a size) added once. Older clients ignore fields they don't know (`ignoreUnknownKeys`). The one exception is the raw-string form for files: an older client shows the whole JSON as the file's name, so that switch waits until most clients read the new form. See [Migration](#migration-and-older-clients).

### 6. Thumbnails and the tiny

- **Every image, video, PDF and sticker upload gets a tiny,** made by the shared pipeline. `THUMBNAILS.md` sets the sizes. Filesync and WebDrop don't, because they store files, not media; contact photos do, instead of throwing theirs away.
- **The quality setting decides the thumbnails and any final image,** passed in by the app (`THUMBNAILS.md`, "Where each caller lands").
- **Where the tiny lives depends on the drive:** readable in the header (`previewThumbnail`) on ordinary drives, so lists render before decrypting; inside encrypted content on zero-knowledge drives, as Photos does. Vault puts it in the readable header today: **Open (decision B).**

### 7. Metadata: the app decides, the builder applies it

Every upload states whether the main file keeps or strips its metadata (location, camera, capture time). The app decides, as `THUMBNAILS.md` already says, and the builder applies it. It no longer happens in the encryption step, which public uploads skip.

| App | Default | Why |
|---|---|---|
| Chat, Moments, feed, stickers | strip | Shared with others; location must never leak by accident. Moments' GPS opt-in stays. |
| Photos | keep | The user's own library; capture time and place are the point. |
| Filesync, WebDrop | keep | They move files; changing the bytes breaks the sync and the transfer. |
| Vault | **Open (decision C)** | The user's own encrypted storage, like Photos, but it strips today. |
| Contact photo | strip | Nobody needs a contact photo's location. It stays on the user's own drive today, and a shared contact card already goes through chat's strip. |
| Profile and card photos | strip | Public. Already re-encoded. |

### 8. Video

- **The quality comes from the app's setting.** Photos backup and Vault transcode to Standard today because nothing sets one (#2386).
- **The original video's name and the recorded fields survive.** The video's metadata is one field of the descriptor, not the whole of it.

### 9. Payload keys

Existing keys stay as they are (`chat_web0`, `mmt_0000`, `photo_main`, `vlt_pg_00`, `sync_data`), because receivers look payloads up by key. New apps use `<app>_<index>`, and the builder names them.

## Per app

What each app sends to the builder, and what it keeps for itself:

| App | Source it acquires | Quality | Metadata | Name | Stays its own |
|---|---|---|---|---|---|
| Chat | picker, camera, paste, share sheet | user setting | strip | picked name | sending, recipients, view-once |
| Moments, feed | as chat | user setting (Standard today) | strip, GPS opt-in | picked name | public posts' plaintext upload |
| Stickers | cut-out, saved image | Original rows | strip | none (neutral) | — |
| Photos | device library export | Original (photos), app setting (videos) | keep | `DISPLAY_NAME` / `originalFilename` | hashing, dedupe, `uniqueId` |
| Vault | picker, scanner, PDF render | Original | decision C | picked name, every file | labels, notes, sections |
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

1. **Readers first.** Every client learns the new descriptor shape and keeps reading the old ones. Ship it.
2. **Writers, app by app,** starting with chat and Moments (already closest), then Vault, stickers and Photos, then Filesync and WebDrop for the parts that apply.
3. **Plain files' raw-string descriptor last,** once most clients read the new shape: an older client would otherwise show the JSON as the name.
4. **Existing files are never rewritten.** Every rule here applies to new uploads.

## Testing

- **One test per app** that builds a typical upload and checks the standard: content type, name, original marker, size, quality, tiny, metadata kept or stripped.
- **A guard** (Konsist): no code outside the builder writes a descriptor or calls the thumbnail pipeline for an upload.
- **Reader tests** for every old encoding, so nothing already stored stops rendering.
- **The metadata rule on the real platforms,** because stripping is per format and per platform: a GPS-tagged JPEG, HEIC and PNG, uploaded by each app, checked for location afterwards.

## Open decisions

| | Decision | Options | Recommendation |
|---|---|---|---|
| **A** | Where the file name lives | (1) the readable descriptor, everywhere; (2) encrypted file content, everywhere; (3) the descriptor on ordinary drives, encrypted content on zero-knowledge drives | (3), unless odin-core can encrypt the descriptor itself on zero-knowledge drives, which would make it (1). To confirm with odin-core. |
| **B** | Vault's tiny is readable in the header on a zero-knowledge drive | keep; move it into encrypted content as Photos does | move it |
| **C** | Vault keeps or strips metadata | keep (it's the user's own storage, like Photos); strip (today) | keep, matching Photos |
| **D** | The plain-file descriptor switch from a raw string to JSON | now, accepting that old clients show JSON as the name; after a release | after a release |

## Bugs found while writing this

Bugs in today's code. The first is filed on its own; the builder fixes the others:

1. **Public feed posts and comments publish location** (#2395, fixed right after the builder lands). They upload through `enqueuePlaintextUpload`, which skips the encryption step, and only that step strips metadata. An Original photo, or a Standard one whose file is kept, is published with its GPS.
2. **A voice note or audio file can be named by its full local path** (`MessageAttachmentBuilder`, `attachment.displayName ?: attachment.filePath`), which puts a device path in the descriptor. Covered by #911's name rule.
3. **Vault keeps only the first file's name** when several are picked (`VaultViewModel`). Covered by #911's migration of Vault.
4. **Saving a chat photo to Vault names it with the date,** not the file name (`ChatMediaFullScreenHost`). Covered by #2393's save-name helper, once #911 records names.
