package id.homebase.core.location.tracking

actual fun createOneShotLocationProvider(): OneShotLocationProvider = UnavailableOneShotLocationProvider

/** Web one-shot GPS is not yet implemented (browser Geolocation API). */
private object UnavailableOneShotLocationProvider : OneShotLocationProvider {
    override suspend fun lastKnownFix(): RawLocationPoint? = null
    override suspend fun acquireFreshFix(timeoutMs: Long): GpsFixResult = GpsFixResult.Unavailable
}
