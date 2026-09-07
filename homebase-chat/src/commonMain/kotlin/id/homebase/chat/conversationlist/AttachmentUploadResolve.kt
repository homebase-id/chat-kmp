package id.homebase.chat.conversationlist

import io.github.vinceglb.filekit.PlatformFile

// The pick-time/send-time materialization helpers (materializeForUpload, toUploadPath) moved to
// id.homebase.core.files.UploadMaterialize in homebase-common - they are the platform-wide answer
// to "I picked a file now and will read it later", not a chat-ism. Only the playback-handle pair
// below is chat-specific enough to stay.

/**
 * A handle the in-editor video decoder/player can read **immediately**, without the expensive
 * okio materialization `toUploadPath` performs (whole-file read + in-memory copy).
 *
 * - Native (Android/iOS/Desktop): the real filesystem path — identical to [PlatformFile.toString];
 *   the native decoders/players read it directly.
 * - Web: a `blob:` object URL minted straight from the picked browser `File` via
 *   `URL.createObjectURL` — O(1), copies no bytes into wasm and uses no base64. The same URL is
 *   reused for the poster, duration probe, filmstrip and playback; the `<video>`/canvas stream
 *   from it. Release it with [revokePlayableUrl] once the attachment is gone.
 *
 * This is what gets stored in `AttachmentPendingFile.FileVideo.playablePath`. The upload pipeline
 * still calls `toUploadPath` at send time to materialize bytes into okio for encryption — that is
 * unaffected by this handle.
 */
expect fun PlatformFile.toPlayableUrl(): String

/** Release a [toPlayableUrl] handle. No-op on native and for non-`blob:` strings. */
expect fun revokePlayableUrl(url: String)
