package id.homebase.api.video

import id.homebase.api.client.drives.files.PayloadHash

/**
 * Result of an HLS segmentation pass: the generated `.m3u8` playlist plus the
 * directory/path holding the `.ts` segments it references.
 *
 * Replaces the opaque `Pair<String, String>` the old `FFmpegUtils.segment*`
 * returned (mirrors how the thumbnail seam introduced [IndexedFrame]) so callers
 * read `.playlistPath` / `.segmentsPath` instead of `.first` / `.second`.
 */
data class SegmentedVideo(
    val playlistPath: String,
    val segmentsPath: String,
    /** The `.ts` payload's hash, when the segmenter took it in its pass; bound to the key header's IV when there is one. */
    val hash: PayloadHash? = null,
)
