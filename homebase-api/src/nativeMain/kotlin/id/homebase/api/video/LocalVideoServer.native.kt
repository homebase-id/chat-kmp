package id.homebase.api.video

actual class LocalVideoServer {
    actual suspend fun start(): String {
        TODO("Not yet implemented")
    }

    actual fun stop() {
    }

    actual fun registerContent(
        id: String,
        data: ByteArray,
        contentType: String,
        authTokenHeaderName: String?,
        authToken: String?
    ) {
    }

    actual fun unregisterContent(id: String) {
    }

    actual fun getContentUrl(id: String): String {
        TODO("Not yet implemented")
    }
}