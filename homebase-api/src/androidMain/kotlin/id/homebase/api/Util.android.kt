package id.homebase.api

private object AndroidPlatform : Platform {
    override val name: PlatformType = PlatformType.ANDROID
}

actual fun getPlatform(): Platform = AndroidPlatform

actual fun isAndroid(): Boolean {
    return true
}

actual fun isIos(): Boolean {
    return false
}

actual fun showMessage(title: String, message: String) {
}
