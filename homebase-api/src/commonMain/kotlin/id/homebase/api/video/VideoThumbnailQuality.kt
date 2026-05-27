package id.homebase.api.video

/**
 * Single source of truth for poster + strip JPEG quality across every platform decoder so
 * thumbnails look the same regardless of which backend produced them.
 *
 * Posters are user-visible at "show this video in the conversation list" sizes — a few hundred
 * pixels wide. Strip frames are the trim-scrubber thumbnails — under 100 px tall. Each format
 * scale is mapped to roughly equivalent perceptual quality:
 *
 * | Backend                | Poster                          | Strip                           |
 * |------------------------|---------------------------------|---------------------------------|
 * | Android `Bitmap`       | `compress(JPEG, 75)`            | `compress(JPEG, 60)`            |
 * | iOS `UIImage`          | `UIImageJPEGRepresentation 0.75`| `UIImageJPEGRepresentation 0.6` |
 * | Web `canvas.toDataURL` | `0.75`                          | `0.6`                           |
 * | ffmpeg `-q:v`          | `4` (≈75%)                      | `7` (≈60%)                      |
 *
 * The ffmpeg `-q:v` (qscale) ladder is 1=best, 31=worst with a non-linear perceptual mapping;
 * 4 and 7 are the values commonly tuned to match libjpeg ≈75 and ≈60 respectively. Sources:
 * ffmpeg-user list discussions and the typical mjpeg/JPEG bitrate tables.
 */
internal object VideoThumbnailQuality {
    // TODO: revisit poster-frame quality strategy in line with how we treat picture posters.
    //   The single-thumb path currently extracts at the final display quality (≈75) in one
    //   shot. The picture pipeline instead extracts/uploads a high-res master and downscales
    //   to multiple thumb tiers (32px feed thumb, 256px message thumb, 1024px viewer). We
    //   should consider doing the same for video posters: extract the poster ONCE at the
    //   highest tier (so quality is good enough that downscaling stays sharp) and reuse that
    //   bitmap as the source for every smaller thumb tier.
    //
    //   NOT in scope for this TODO: the trim-scrubber strip — those frames are dedicated to
    //   one specific UI surface, have their own size budget, and are never downscaled into
    //   anything else. Keep them at STRIP_* quality.

    /** 0–100 scale for `Bitmap.compress` (Android). */
    const val POSTER_JPEG_QUALITY_0_TO_100: Int = 75

    /** 0.0–1.0 scale for `UIImageJPEGRepresentation` (iOS) and `canvas.toDataURL` (web). */
    const val POSTER_JPEG_QUALITY_0_TO_1: Double = 0.75

    /** ffmpeg `-q:v` qscale (1 best, 31 worst). 4 ≈ JPEG quality 75. */
    const val POSTER_FFMPEG_QSCALE: Int = 4

    const val STRIP_JPEG_QUALITY_0_TO_100: Int = 60
    const val STRIP_JPEG_QUALITY_0_TO_1: Double = 0.6

    /** Legacy alias for old `-q:v 5` strip default — used until normalization. */
    const val STRIP_FFMPEG_QSCALE: Int = 7

    /** Convenience for files that previously hard-coded `5` as the ffmpeg strip qscale. */
    const val FFMPEG_QSCALE: Int = STRIP_FFMPEG_QSCALE
}
