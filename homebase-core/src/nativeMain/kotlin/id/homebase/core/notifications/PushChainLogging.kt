package id.homebase.core.notifications

import co.touchlab.kermit.Logger

/**
 * Swift-callable breadcrumb for the push→capture→upload chain (#988) — lands in
 * homebase.log via Kermit's file writer (initialized by `initializeApp()` in
 * `didFinishLaunchingWithOptions`, which UIKit guarantees runs before any
 * `didReceiveRemoteNotification` delivery). Mirrors the `logPrevention` pattern
 * (IosGpuTextDiagnostics). Exported as `PushChainLoggingKt.logPushChain(hop:detail:)`.
 *
 * Hops 2+ are logged by the shared commonMain pipeline (NotificationEntry →
 * LocationService → uploader) under the same tag — Swift only owns hop 1 (arrival),
 * the single genuinely platform-specific step.
 */
fun logPushChain(hop: String, detail: String) {
    Logger.i(tag = "PushCapture") { "$hop: $detail" }
}
