package id.homebase.api.video

/**
 * Stage the bundled `sample.mp4` fixture in a form the platform's decoder can read, returning
 * a [String] path the decoder accepts (filesystem path on JVM/iOS, okio in-memory path on Web).
 *
 * Returns `null` when this test target has no fixture wired up yet — the calling test should
 * skip and exit green. Today only the JVM target stages a real fixture; iOS / Web / Android
 * actuals return null until per-target fixture and bridge plumbing lands.
 */
internal expect suspend fun stageSampleVideoForFfmpegTest(): String?

/** Best-effort cleanup of the staged fixture. */
internal expect suspend fun cleanupStagedSampleVideo(path: String)
