package id.homebase.core.util

import platform.Foundation.NSBundle

class IOSPlatformInfo: PlatformInfo {
    override val versionName: String
        get() = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "Unknown"

    override val versionCode: Int
        get() = (NSBundle.mainBundle.infoDictionary?.get("CFBundleVersion") as? String)?.toIntOrNull() ?: 0

    // APNs / BGTask can cold-wake the process headless.
    override val supportsBackgroundWake: Boolean = true
}