package id.homebase.api

actual fun isAndroid(): Boolean {
    return false
}

actual fun isIos(): Boolean {
    return true
}

actual fun getPlatform(): Platform {
    TODO("Not yet implemented")
}

actual fun showMessage(title: String, message: String) {
}
