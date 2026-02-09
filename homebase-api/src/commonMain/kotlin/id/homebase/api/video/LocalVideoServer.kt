package id.homebase.api.video

expect class LocalVideoServer {
    suspend fun start(): String
    fun stop()

    fun registerContent(
        id: String,
        data: ByteArray,
        contentType: String,
        authTokenHeaderName: String?,
        authToken: String?
    )

    fun unregisterContent(id: String)
    fun getContentUrl(id: String): String
}
