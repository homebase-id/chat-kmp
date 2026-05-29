// Auto-loaded by Kotlin/Wasm Karma integration. Boots the ffmpeg.wasm bridge
// (globalThis.__odinFfmpeg) before any test code runs, so FfmpegDecoderCommonTest
// on wasmJs can exercise the real ffmpeg fallback through FFmpegWasmVideoDecoder.
//
// Files come from src/wasmJsTest/resources/, which the
// `mirrorWebAppFfmpegAssetsForTest` gradle task populates from webApp's
// wasmJsMain/resources/.

config.files = (config.files || []).concat([
    { pattern: "kotlin/mp4box.all.min.js", included: true, watched: false, served: true },
    { pattern: "kotlin/ffmpeg-wrapper.js", included: true, watched: false, served: true },
    { pattern: "kotlin/odin-ffmpeg.js", included: true, watched: false, served: true },
    // The core .wasm + esm loader are fetched at runtime by odin-ffmpeg.js — serve them but
    // do NOT auto-load (included: false).
    { pattern: "kotlin/ffmpeg-wasm/**/*", included: false, watched: false, served: true },
]);

// odin-ffmpeg.js resolves core URLs against document.baseURI; Karma defaults to /. The Kotlin
// test bundle's HTML harness sits under /base/ and serves resources under /base/kotlin/. Map a
// stable /ffmpeg-wasm/ URL so the loader's relative paths resolve regardless of where Karma
// mounts the harness.
config.proxies = Object.assign({}, config.proxies || {}, {
    "/ffmpeg-wasm/": "/base/kotlin/ffmpeg-wasm/",
});
