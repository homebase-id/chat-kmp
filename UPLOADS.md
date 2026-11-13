# The user upload format

**Status: decided (Michael, 2026-11-13), not built yet.** Built by #911. Thumbnail sizes and the tiny are specified in [`THUMBNAILS.md`](THUMBNAILS.md); this document says where they go.

## What a user upload is

**A user upload is a file the user puts into Homebase through one of our apps:** a photo or video sent in chat, a voice note, a document, a Moment or feed post with media, a saved sticker, a Photos backup, a Vault document, a Filesync file, a WebDrop file, a contact or profile photo. The user picked it, captured it, or the app exported it from their device for them.

**Not user uploads:** data the app generates itself, such as message text, reactions, read receipts, location tracks, settings, link and location previews' own data. They keep their own formats. Link and location previews do use this descriptor (section 1), because they ride along with a message's payloads.

Every user upload, in every app, is assembled by one builder into the format below. Apps only acquire the source (picker, camera, export, crop) and choose options.

## Contents

1. [The descriptor content](#1-the-descriptor-content)
2. [Encrypted descriptor content](#2-encrypted-descriptor-content)
3. [The rules](#3-the-rules)
4. [The builder](#4-the-builder)
5. [Per app](#5-per-app)
6. [Reading old descriptors](#6-reading-old-descriptors)
7. [Rollout](#7-rollout)
8. [Testing](#8-testing)
9. [Decisions](#9-decisions)
10. [Today, for reference](#10-today-for-reference)
11. [Bugs found while surveying](#11-bugs-found-while-surveying)

## Words

| Word | Meaning |
|---|---|
| **HomebaseFile** | What an upload creates on a drive: a header (`appData`) plus payloads. A chat message with three photos is one HomebaseFile. |
| **Payload** | A payload of the HomebaseFile, under a key such as `chat_web0`: usually one picked photo, video or document. |
| **Payload data** | A payload's bytes: what the user picked, or the smaller copy we made of it. |
| **Descriptor content** | A payload's `descriptorContent` string: the one field of the server's `PayloadDescriptor` record that is ours. |

## 1. The descriptor content

### Where it sits

A HomebaseFile has one payload per picked item. The server keeps a fixed, plain record of each payload, its `PayloadDescriptor`. The only part of that record that is ours to define is one string, `descriptorContent`:

```text
HomebaseFile (a chat message with 3 photos)
├── header (appData: content, previewThumbnail = the file's tiny, ...)
├── payload chat_web0 → PayloadDescriptor (server's record, plain)
│     ├── key, contentType ("image/webp"), iv, bytesWritten, lastModified
│     ├── thumbnails: [ThumbnailDescriptor 240×427, ThumbnailDescriptor 480×854]   sizes, plain
│     ├── previewThumbnail: ThumbnailDescriptor 11×20, bytes inline                the tiny, plain
│     └── descriptorContent: String                                                ours: DescriptorContent, below
├── payload chat_web1 → PayloadDescriptor ...
└── payload chat_web2 → PayloadDescriptor ...
```

- **Thumbnails belong to one payload** (at most 5) and are fetched by HomebaseFile, payload key and size. Their sizes are in the plain `thumbnails` list.
- **`contentType`** is the payload data's format (`image/jpeg`, `image/webp`). **`kind`**, below, is how to show the payload: an `image/png` can be a photo, a sticker or a document.
- **A view decrypts descriptor content only when it needs it:** to step up, open full screen, show info or save. Never to lay out or pick a thumbnail; those use the plain `thumbnails` and `previewThumbnail`. Decrypting and parsing one costs about 2.5 µs on a desktop JVM.

### The format

```kotlin
@Serializable
data class DescriptorContent(
    val v: Int = 1,
    val kind: Kind,                    // how to show it
    val name: String? = null,          // original file name; rules in 3.2
    val original: Boolean? = null,     // image, video, sticker: payload data is the user's file, byte for byte
    val pixelWidth: Int? = null,       // image, video, sticker: payload data's pixels, upright
    val pixelHeight: Int? = null,
    val quality: Quality? = null,      // image, video
    val lengthSeconds: Int? = null,    // audio
    val video: VideoMetadata? = null,  // video: today's VideoMetadata, unchanged
    val appContent: String? = null,    // app-defined; only the app that wrote it reads it
) {
    @Serializable enum class Kind { image, video, audio, file, sticker, app }
    @Serializable enum class Quality { sd, hd, org }
}
```

The dog photo's payload, sent at Standard in a public post:

```json
{
  "key": "chat_web0",
  "contentType": "image/webp",
  "bytesWritten": 231120,
  "thumbnails": [{"pixelWidth": 240, "pixelHeight": 427}, {"pixelWidth": 480, "pixelHeight": 854}],
  "previewThumbnail": {"pixelWidth": 11, "pixelHeight": 20, "content": "UklG…"},
  "descriptorContent": "{\"v\":1,\"kind\":\"image\",\"name\":\"PXL_20261107_083131163.jpg\",\"original\":false,\"pixelWidth\":1152,\"pixelHeight\":2048,\"quality\":\"sd\"}"
}
```

In an encrypted chat, `descriptorContent` holds the same JSON encrypted (section 2).

- **`quality`** is always written for images and videos. `sd`, `hd` and `org` replace the old `standard`, `hd` and `high` in this format only; readers translate old `ImageFile` descriptors (section 6), and saved settings keep their codes.
- **`appContent`** is for payloads that aren't user uploads but ride on a HomebaseFile: chat's link previews (`chat_links`: URL, title, description) and location previews (`chat_loc`: coordinates, address), and Vault notes' preview text. Each app keeps its own format inside it, with `kind = app`.
- **No separate extension field.** The original extension is in `name`; the saved file's extension comes from `contentType`.
- **Size:** the server allows 1,024 characters of descriptor content, about 730 once encrypted. Over that, the whole descriptor content moves to its own encrypted payload, `pld_desc<index>`, as today.

## 2. Encrypted descriptor content

When the HomebaseFile is encrypted, `descriptorContent` holds the descriptor encrypted, in the shape both clients already use for encrypted query strings and socket messages (`SharedSecretEncryptedPayload`):

```json
{"iv":"<base64, 16 random bytes>","data":"<base64, AES-CBC-PKCS7(fileAesKey, iv, UTF-8 DescriptorContent JSON)>"}
```

| Item | Value |
|---|---|
| Key | The HomebaseFile's AES key (`KeyHeader.aesKey`), the one its payloads use |
| IV | Fresh random 16 bytes per descriptor. Never the payload's IV, which already encrypts the payload's bytes with the same key |
| When | The HomebaseFile is encrypted (`isEncrypted`). Unencrypted HomebaseFiles (public posts) store the plain `DescriptorContent` |
| Size | odin-core allows 1,024 characters. Descriptor content over about 730 characters (encrypted) or 1,024 (plain) moves to its own payload `pld_desc<index>`, as today |
| Server | No change. odin-core stores, copies and length-checks descriptors, and never reads them |

Why the whole descriptor content: besides names, it carries location coordinates and addresses, link URLs and titles (in `appContent`), and Vault note previews.

## 3. The rules

### 3.1 Content type

First match wins:

1. **The bytes:** `ImageFormatDetector.detectFormat`, then the video and audio probes.
2. **The platform's MIME type,** if not blank and not `application/octet-stream`.
3. **The name's extension** (`ContentTypeUtil`).
4. `application/octet-stream`.

Filesync and WebDrop still store `application/octet-stream` payloads (they move bytes), but record the detected type in their own content.

### 3.2 Name

```text
name = basename(pickedName)          // never a path
name = NFC(name)
replace "/", "\", NUL and control characters with "_"
trim whitespace
over 255 UTF-8 bytes: cut the part before the last dot, on a character boundary; never cut the extension
  (an "extension" over 16 characters isn't one: cut the whole name)
blank → null
```

- **No name** (null) for a camera capture, a pasted image, a recorded voice note. Saving then uses a dated name (#2393).
- **Stickers** always null; they save as `StickerFile.<format>`.
- **Re-encoding keeps the name.** `IMG_1234.HEIC` sent at Standard is stored as `IMG_1234.HEIC`; saving swaps the extension for the saved bytes' type (#2393).
- **An app's own editable name is separate.** Vault's label and a Filesync rename live in the app's content; saving prefers them.

### 3.3 Original or copy

`original = true` when the payload data is the user's file byte for byte (Original, Photos, Vault, a GIF, a kept source). `false` when the builder made it (Standard or HD final image, a transcoded video). Views step up to the payload data only when `original == false` and `pixelWidth` is set (`THUMBNAILS.md`, "Showing the images").

### 3.4 Metadata

```kotlin
enum class MetadataPolicy { KEEP, STRIP }
```

Applied by the builder, before the encrypted and public paths split, so public uploads are stripped exactly like encrypted ones (#2395). The encryption step no longer strips.

| Payload | STRIP does | KEEP does |
|---|---|---|
| JPEG, PNG, WebP, HEIF | remove EXIF, XMP and IPTC without re-encoding (today's scrubber) | nothing |
| AVIF, TIFF | re-encode to WebP at the setting's quality | nothing |
| GIF | nothing (GIF has no standard location field) | nothing |
| Final image (Standard, HD) | already has none | copy the source's metadata into it |
| Video | `-map_metadata -1` (transcode or remux) | keep the container's metadata |
| Audio, files | nothing | nothing |

### 3.5 Tiny and thumbnails

| Item | Rule |
|---|---|
| Who gets them | Every `image`, `video`, `sticker` and PDF `file`. Not `audio` or other files. Link and location previews keep their tiny-only previews |
| Sizes | `THUMBNAILS.md`, for the app's quality |
| Tiny location | Plain, in the payload's `previewThumbnail` and the header's `appData.previewThumbnail` (the smallest tiny), encrypted file or not. 20 px leaks nothing meaningful |
| Thumbnails | Encrypted with the payload, as today |

### 3.6 Payload keys

`<prefix><index>`, prefix per app (section 5), index from 0, no padding for new prefixes. Existing prefixes keep their format, because receivers look payloads up by key.

## 4. The builder

In `homebase-upload`:

```kotlin
data class UploadSource(
    val path: String,                // local file to upload
    val pickedName: String?,         // as the device showed it; null if none
    val platformMimeType: String?,
    val kindHint: DescriptorContent.Kind? = null,   // sticker, or a file sent as a document: the app says so
    val appContent: String? = null,  // for kind = app
)

data class UploadOptions(
    val keyPrefix: String,           // "chat_web"
    val quality: MediaQuality,       // decides thumbnails, final image, video transcode
    val metadata: MetadataPolicy,
    val encrypted: Boolean,          // encrypts descriptors (section 2); payload encryption stays in UploadService
)

suspend fun buildUserUpload(
    sources: List<UploadSource>,
    options: UploadOptions,
    keyHeader: KeyHeader?,           // required when encrypted
): UserUpload

data class UserUpload(
    val payloads: List<PayloadFile>,         // descriptorContent filled, plain or encrypted
    val thumbnails: List<ThumbnailFile>,
    val headerTiny: EmbeddedThumb?,          // for appData.previewThumbnail
    val descriptors: List<DescriptorContent>, // plain copies, for apps that also keep the name in their content
)
```

- Calls `createMediaImages` / `planMediaImages` for images and stickers, the video processor for videos, and takes a PDF page render from the app.
- `MessageAttachmentBuilder`, `MessageThumbnailGenerator`, `FinalImagePayload`, `LinkPreviewPayloadBuilder`, `LocationPreviewPayloadBuilder` and Vault's and Photos' assembly fold into it.
- `UploadService` (encrypted) and `enqueuePlaintextUpload` (public) both take a `UserUpload`; neither builds or changes descriptors.

## 5. Per app

| App | `keyPrefix` | `quality` | `metadata` | `encrypted` | Transport |
|---|---|---|---|---|---|
| Chat | `chat_web` | user setting | STRIP | yes | `UploadService` |
| Moments posts / comments | `mmt_` / `mmc_` (existing 4-digit index) | user setting | STRIP (GPS opt-in kept) | per audience | `UploadService` or public |
| Feed posts / comments | `pst_mdi_` / `cmmnt_md` | user setting | STRIP | per audience | `UploadService` or public |
| Stickers (saved) | `sticker_` | ORIGINAL | STRIP | yes | `UploadService` |
| Photos | `photo_main`, `photo_live`, `video_main` | ORIGINAL (photos), app setting (videos, #2386) | KEEP | yes | its own outbox path (keeps hashing, dedupe, `uniqueId`) |
| Vault | `vlt_pg_` (existing 2-digit index) | ORIGINAL | KEEP | yes | `UploadService` |
| Filesync | `sync_data` | none (bytes unchanged) | KEEP | yes | its own direct upload |
| WebDrop | existing | none | KEEP | link key | its own outbox path |
| Contact photo | none | its own sizes | STRIP | yes | contacts image API |
| Profile and card photos | none | its own sizes | STRIP | server-side | profile attribute API |

Filesync, WebDrop and the two photo APIs use the builder's rules (content type, name, metadata, tiny) but keep their transport.

## 6. Reading old descriptors

`PayloadDescriptor.descriptorInfo()` decides in this order:

| `descriptorContent` | Read as |
|---|---|
| null | nothing |
| JSON object with exactly `iv` and `data` | encrypted: decrypt with the HomebaseFile's key, then read the result with this table |
| JSON object with `"v": 1` | `DescriptorContent` |
| anything else | today's rules by content type: `AudioFile`, markdown preview, `VideoMetadata`, `ImageFile`, link and location arrays, or the raw string as a file name |

Old files are never rewritten.

## 7. Rollout

**One switch, at once** (decision D): readers and writers for every app and kind, in chat-kmp and odin-js, in the same release. An app that hasn't updated shows a new upload's descriptor as text until it does.

odin-js reads descriptors in `community-app` (chat, Community), `common-app` (feed), `web-drop-app` and `ui-lib` (video), and writes them in `community-app` and `js-lib` (`PayloadAndThumbnailGenerator`, `PostUploadHelpers`).

## 8. Testing

| Test | Checks |
|---|---|
| `DescriptorContent` round trip | Every kind serialises and parses; unknown fields ignored; old quality codes translate |
| Encrypted descriptor | Encrypt then decrypt with the HomebaseFile's key; fresh IV each time; over-length moves to `pld_desc` |
| Old descriptors | Each row of section 6, with real stored examples, reads as before |
| Name rules | Paths, NFC, `/` and NUL, a long name keeps its extension, an emoji at the cut, a 20-character "extension", blank |
| Content type | Wrong platform MIME, empty MIME, no extension, renamed file |
| Metadata | A GPS-tagged JPEG, HEIC, PNG, AVIF and a video, per policy, on Android, iOS, desktop and web, encrypted and public |
| Per app | One typical upload per app produces section 5's options |
| Guard (Konsist) | No code outside the builder writes `descriptorContent` or calls the thumbnail pipeline for an upload |

## 9. Decisions

| | Decision |
|---|---|
| **A** | The name lives in the descriptor; an encrypted HomebaseFile encrypts its payloads' whole descriptors (section 2), in both clients. |
| **B** | The tiny stays plain on every file. |
| **C** | Vault keeps metadata. |
| **D** | One switch, at once, for every kind including plain files. Unencrypted files' descriptors stay plain. |

## 10. Today, for reference

| App | Name stored | Content type | Metadata | Tiny |
|---|---|---|---|---|
| Chat | files: the raw string; audio: `AudioFile.name`; photos and videos: none | picked MIME, else extension | stripped in the encryption step | plain |
| Moments, feed | as chat | `file.contentType()` | stripped only when encrypted (#2395) | plain |
| Stickers | none | caller's | stripped | plain |
| Photos | `PhotoContent.name`, encrypted | platform MIME, can be `""` | kept | in encrypted content |
| Vault | `VaultFileContent.name`, first file only | `PlatformFile.contentType()` | stripped | plain |
| Filesync | `FilesyncNodeContent.name` | `application/octet-stream` | kept | none |
| WebDrop | its manifest | `application/octet-stream` | kept | none |
| Contact photo | none | `PlatformFile.contentType()` | kept | discarded |
| Profile photos | none | `image/webp` | removed by re-encode | yes |

## 11. Bugs found while surveying

| Bug | Fixed by |
|---|---|
| Public feed posts and comments publish GPS (the public path skips the strip) | #2395, right after the builder |
| Audio named by its full device path (`displayName ?: filePath`) | the name rule, 3.2 |
| Vault records only the first picked file's name | the builder, per payload |
| "Save to Vault" from chat names the file with a date | #2393's save-name helper |
