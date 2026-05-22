package id.homebase.api

actual fun isAndroid(): Boolean {
    return false
}

actual fun isIos(): Boolean {
    return true
}

private object IosPlatform : Platform {
    override val name: PlatformType = PlatformType.IOS
}

actual fun getPlatform(): Platform = IosPlatform

actual fun showMessage(title: String, message: String) {
}
