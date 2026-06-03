package id.homebase.core.util

interface PlatformInfo {
    val versionName: String
    val versionCode: Int

    /**
     * True on platforms whose OS can wake the process in the background without a
     * user-facing UI (Android FCM data messages, iOS APNs/BGTask). Such a wake
     * fires `YouAuthState.Authenticated` while no one is looking at the screen, so
     * [id.homebase.core.auth.AuthConnectionCoordinator] starts headless on these
     * platforms and waits for `promoteToForeground()` before opening the WebSocket,
     * running the UI preload, or loading the profile.
     *
     * False on platforms with no background-wake mechanism (Desktop/JVM, Web): the
     * process only runs when a user launched it, so the coordinator starts in
     * foreground mode and connects on `Authenticated` directly. This makes a missing
     * `promoteToForeground()` call fail open (connect) instead of fail closed (hang
     * on "syncing").
     */
    val supportsBackgroundWake: Boolean
}