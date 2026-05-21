# Moments media widgets — usage

Two composables forked from chat for use in Moments screens. Both live in
package `id.homebase.core.ui.screens.moments.widget`.

## MomentMediaItem — single media payload

Renders one image, video, audio, document, or link/location preview based on
`payload.contentType` / `payload.key`.

```kotlin
MomentMediaItem(
    payload = payload,                 // PayloadDescriptor (from the moment file)
    fileId = momentFileId,             // Uuid — the file's id on the drive
    driveId = momentsDriveId,          // Uuid — the moments drive id
    keyHeader = momentKeyHeader,       // KeyHeader — the moment's master key header
    modifier = Modifier.fillMaxWidth(),
    sharedTransitionScope = null,      // pass real scopes if using shared-element transitions
    animatedVisibilityScope = null,
)
```

Useful optional parameters:
- `preserveAspectRatio = true` — fit by aspect ratio (good for detail screens). Default
  crops to fill.
- `imageSize` — `ImageSize.THUMB_SMALL/MEDIUM/LARGE/XLARGE`. Default `THUMB_MEDIUM`.
- `onClick` / `onLongPress` — gesture callbacks.
- `messageId` — the moment id; required if you want to read pre-upload local
  attachment context (selected-but-not-yet-posted media).
- `isUploading` — `true` while the post is uploading; hides the play icon and
  preload spinner on videos.

## MomentMediaGallery — multiple payloads

Renders a 1 / 2 / 3 / 4+ grid (4+ shows a "+N" overlay on the 4th cell). Internally
calls `MomentMediaItem` for each cell.

```kotlin
MomentMediaGallery(
    payloads = moment.payloads,        // List<PayloadDescriptor>
    fileId = momentFileId,             // Uuid
    driveId = momentsDriveId,          // Uuid
    keyHeader = momentKeyHeader,       // KeyHeader
    messageId = moment.id,             // Uuid — the moment id
    downloadingFiles = emptySet(),     // Set<String>; "${messageId}_${payload.key}" entries
    sharedTransitionScope = null,
    animatedVisibilityScope = null,
    onMediaClick = { payload -> /* navigate to viewer */ },
    onMediaLongPress = { payload, offset -> /* show menu */ },
)
```

## Which one to use

- 1 payload → either works (gallery delegates to item for `payloads.size == 1`).
- 2+ payloads → `MomentMediaGallery`.

## What you need to provide

Whatever owns the moments data needs to expose these per-moment:
`PayloadDescriptor` list, `fileId`, `driveId`, `keyHeader`, and a `Uuid` id.
Without those, neither composable can render encrypted media.
