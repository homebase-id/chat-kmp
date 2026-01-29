package id.homebase.core.util

actual fun isDesktop(): Boolean {
    return true
}

actual fun isMobile(): Boolean {
    return false
}

actual fun isWeb(): Boolean {
    return false
}

actual fun isDesktopOrWeb(): Boolean {
    return true
}