package id.homebase.api.video

/**
 * Reports a `0f..1f` completion fraction for a long-running ffmpeg operation
 * (compress / segment). Named so the compression seam reads consistently instead
 * of passing bare `(Float) -> Unit` lambdas around.
 *
 * The *phase* a fraction belongs to (compressing vs segmenting) is the caller's
 * concern — see [VideoPayloadProcessor], which wraps each fraction into a
 * [VideoPayloadProgressPhase].
 *
 * **Threading: may be invoked off the main thread.** Mid-run ticks come from the
 * platform's ffmpeg engine on its own thread (e.g. iOS FFmpegKit's statistics
 * callback fires from a background thread); the terminal `1f` runs on the coroutine
 * that called the operation. A listener that touches UI state must marshal onto the
 * main dispatcher itself — do not assume any particular thread. Keep the body cheap
 * and non-blocking; it can be called frequently during an encode.
 */
typealias VideoProgressListener = (fraction: Float) -> Unit
