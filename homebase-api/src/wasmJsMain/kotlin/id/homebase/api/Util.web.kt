package id.homebase.api

private object WebPlatform : Platform {
    override val name: PlatformType = PlatformType.JS
}

actual fun getPlatform(): Platform = WebPlatform

actual fun isAndroid(): Boolean = false

actual fun isIos(): Boolean = false

actual fun showMessage(title: String, message: String) {
    // No-op for now; a later step can wire this to window.alert or a snackbar.
}
