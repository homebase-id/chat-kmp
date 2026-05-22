package id.homebase.api

private object JvmPlatform : Platform {
    override val name: PlatformType = PlatformType.JVM
}

actual fun getPlatform(): Platform = JvmPlatform

actual fun isAndroid(): Boolean {
    return false
}

actual fun isIos(): Boolean {
    return false
}

actual fun showMessage(title: String, message: String) {
}
