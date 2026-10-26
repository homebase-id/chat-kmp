package id.homebase.core.util

actual fun isDesktop(): Boolean {
    return false
}

actual fun isMobile(): Boolean {
    return false
}

actual fun isWeb(): Boolean {
    return true
}

actual fun isDesktopOrWeb(): Boolean {
    return true
}

actual fun isNativeMobile(): Boolean {
    return false
}

actual fun inputCapabilities(): InputCapabilities = InputCapabilities(
    primaryTouch = mediaMatches("(pointer: coarse)"),
    anyTouch = mediaMatches("(any-pointer: coarse)") || maxTouchPoints() > 0,
    anyHover = mediaMatches("(any-hover: hover)"),
)

private fun mediaMatches(query: String): Boolean =
    js("(typeof window !== 'undefined' && typeof window.matchMedia === 'function') && window.matchMedia(query).matches")

private fun maxTouchPoints(): Int =
    js("(typeof navigator !== 'undefined' && navigator.maxTouchPoints) || 0")
