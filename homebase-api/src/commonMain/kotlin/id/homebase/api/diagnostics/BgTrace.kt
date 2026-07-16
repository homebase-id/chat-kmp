package id.homebase.api.diagnostics

import co.touchlab.kermit.Logger

/**
 * One greppable tag for the app's background-observability baseline (#1109). All lines share the
 * `BgTrace` Kermit tag so a day's `homebase.log` yields a clean background breakdown from a single
 * `grep BgTrace` — instead of hand-correlating scattered `LiveRelay` / GPS-profile / WS logs.
 *
 * Line shapes are built by the pure formatter functions below so they can be unit-tested and stay
 * stable for downstream log tooling. This is log-only; nothing here changes behavior.
 *
 * Categories emitted today:
 * - `transition …`  — foreground↔background flip with the duration of the window just ended.
 * - `ws-connect …`  — a notify-WS connect attempt, tagged with foreground/background state. A
 *                     `state=bg` attempt is the red flag #1108 targets (a background reconnect with
 *                     no reason to be connected).
 * - `wake cause=…`  — why the process was active in the background (fcm / location-delivery /
 *                     ws-recv / live-share-send).
 *
 * Deferred to companion issues (documented so the format stays consistent when they land):
 * - `ip-target …`   — stored-ip vs dns connect-target decision → lands with #1107 (the code that
 *                     makes the choice).
 */
object BgTrace {
    const val TAG = "BgTrace"

    /**
     * A foreground↔background transition. [windowMs] is the duration of the window that just ended
     * (time spent in the *previous* state). [profile] is the active location tracking profile, or
     * null when unknown (omitted from the line).
     */
    fun transition(toForeground: Boolean, windowMs: Long, profile: String?): String =
        "transition ${if (toForeground) "bg->fg" else "fg->bg"} windowMs=$windowMs" +
            (profile?.let { " profile=$it" } ?: "")

    /** A notify-WS connect attempt, tagged with the app's foreground/background state. */
    fun wsConnect(foreground: Boolean, url: String): String =
        "ws-connect state=${if (foreground) "fg" else "bg"} url=$url"

    /** Why the process was active in the background. [cause] is a stable slug; [detail] is free-form. */
    fun wake(cause: String, detail: String): String = "wake cause=$cause $detail"

    /** Emit a pre-formatted [line] under the shared [TAG]. */
    fun log(line: String) = Logger.i(tag = TAG) { line }
}
