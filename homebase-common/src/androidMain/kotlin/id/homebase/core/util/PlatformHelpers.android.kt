package id.homebase.core.util

actual fun isDesktop(): Boolean {
    return false
}

actual fun isMobile(): Boolean {
    return true
}

actual fun isWeb(): Boolean {
    return false
}

actual fun isDesktopOrWeb(): Boolean {
    return false
}

actual fun isNativeMobile(): Boolean {
    return false
}