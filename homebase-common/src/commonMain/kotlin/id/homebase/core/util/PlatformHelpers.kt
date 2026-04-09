package id.homebase.core.util

expect fun isDesktop(): Boolean

expect fun isMobile(): Boolean

expect fun isWeb(): Boolean

expect fun isDesktopOrWeb(): Boolean

/** True on iOS (native) targets where ComposeUIViewController handles keyboard avoidance. */
expect fun isNativeMobile(): Boolean