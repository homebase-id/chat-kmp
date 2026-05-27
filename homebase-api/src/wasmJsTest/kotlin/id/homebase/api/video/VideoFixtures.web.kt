package id.homebase.api.video

// wasmJs test target doesn't yet ship a sample fixture into its browser harness; the common
// test exits early and passes green here. Wire this up when the wasmJsTest target gets
// configured to load fixture bytes (e.g. via `fetch()` from a Karma-served path or an
// embedded base64 string) into the okio FakeFileSystem alongside the `__odinFfmpeg` bridge.
internal actual suspend fun stageSampleVideoForFfmpegTest(): String? = null

internal actual suspend fun cleanupStagedSampleVideo(path: String) {
    // no-op
}
