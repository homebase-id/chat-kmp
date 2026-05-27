package id.homebase.api.video

// iOS test target doesn't yet ship a sample fixture into its bundle; the common test exits
// early and passes green here. Wire this up when iOS XCTest integration with FFmpegKitBridge
// lands.
internal actual suspend fun stageSampleVideoForFfmpegTest(): String? = null

internal actual suspend fun cleanupStagedSampleVideo(path: String) {
    // no-op: nothing was staged
}
