package id.homebase.api.client

/**
 * True when [this] (or anything in its [Throwable.cause] chain) is a failure from the
 * on-device ML Kit / MediaPipe image-segmentation subsystem used for background removal.
 *
 * ML Kit Subject Segmentation runs on MediaPipe's native pipeline. Its teardown/`close()`
 * and late result callbacks run on threads we don't own, so a failure there can surface as
 * an *uncaught* exception that no local `try/catch` around `removeBackground()` can reach —
 * the same class of problem as a dropped-socket leaking to the global handler (see
 * [isTransientNetworkFailure], PR #737). Background removal is a best-effort enhancement: a
 * failure must degrade to "no cutout", never kill the app.
 *
 * The platform uncaught-exception handlers consult this (alongside
 * [isTransientNetworkFailure]) and record a Crashlytics non-fatal instead of crashing.
 *
 * Matched narrowly to avoid masking unrelated crashes: an exception class in the
 * `com.google.mlkit` / `com.google.mediapipe` packages (qualified name carries the
 * marker), or a `mediapipe` marker in the message. Walks the cause chain defensively
 * (guards against a cyclic chain).
 */
fun Throwable.isMlKitTeardownFailure(): Boolean {
    val seen = HashSet<Throwable>()
    var cur: Throwable? = this
    while (cur != null && seen.add(cur)) {
        val className = cur::class.qualifiedName?.lowercase()
        if (className != null && ("mediapipe" in className || "mlkit" in className)) return true
        val message = cur.message?.lowercase()
        if (message != null && "mediapipe" in message) return true
        cur = cur.cause
    }
    return false
}
