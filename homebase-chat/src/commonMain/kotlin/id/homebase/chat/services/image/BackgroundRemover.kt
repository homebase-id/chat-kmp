package id.homebase.chat.services.image

/**
 * On-device subject / foreground segmentation: turn a photo into a transparent
 * cut-out so it can be sent as a sticker (PR #664's isSticker / forceSticker path).
 *
 * This is a STANDALONE expect/actual rather than a method on `ImageUtils` on
 * purpose. `ImageUtils` is a shared Skia-backed object (one actual covers
 * Desktop/iOS/Web via `skiaMain`), but a background remover needs three genuinely
 * different native engines that the Skia sharing structurally forbids:
 *
 *  - Android  → ML Kit Subject Segmentation (Play-services on-device model).
 *  - iOS      → Vision `VNGenerateForegroundInstanceMaskRequest` (Neural Engine).
 *  - JVM/Web  → unsupported in v1 (`return null`).
 *
 * Each platform places its own `actual` in `androidMain` / `nativeMain` /
 * `jvmMain` / `wasmJsMain` (NOT a shared source set).
 *
 * @return a transparent PNG (alpha-cut foreground) on success, or `null` when:
 *   - the platform is unsupported (Desktop/Web, iOS Simulator / no Neural Engine),
 *   - the on-device model isn't available yet (ML Kit model not downloaded, no GMS),
 *   - or no confident foreground subject was found.
 * `null` is a soft outcome — callers leave the original image untouched and
 * surface a "no subject found" message; it never throws for these cases.
 *
 * Suspends so each platform can run the segmenter off the main thread.
 */
expect suspend fun removeBackground(srcBytes: ByteArray): ByteArray?

/**
 * Whether [removeBackground] can plausibly produce a cut-out on this platform/device.
 *
 * The attachment editor calls this to decide whether to show the "Remove
 * background" tool at all, so unsupported platforms (Desktop/Web, iOS Simulator)
 * present no dead control. It is a fast, side-effect-free capability probe — a
 * `true` result does NOT guarantee a subject will be found in a given image
 * (that still returns `null` from [removeBackground]); a `false` result means the
 * engine is structurally unavailable here.
 */
expect fun isBackgroundRemovalSupported(): Boolean
