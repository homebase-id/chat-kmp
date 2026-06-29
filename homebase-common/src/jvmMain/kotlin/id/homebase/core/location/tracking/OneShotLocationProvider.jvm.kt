package id.homebase.core.location.tracking

actual fun createOneShotLocationProvider(): OneShotLocationProvider = UnavailableOneShotLocationProvider

/** Desktop has no GPS. */
private object UnavailableOneShotLocationProvider : OneShotLocationProvider {
    override suspend fun lastKnownFix(): RawLocationPoint? = null
    override suspend fun acquireFreshFix(timeoutMs: Long): GpsFixResult = GpsFixResult.Unavailable
}
