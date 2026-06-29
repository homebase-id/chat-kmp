package id.homebase.core.location.tracking

actual fun createOneShotLocationProvider(): OneShotLocationProvider = UnavailableOneShotLocationProvider

/** Desktop has no GPS. */
private object UnavailableOneShotLocationProvider : OneShotLocationProvider {
    override suspend fun getCurrentFix(timeoutMs: Long, maxAgeMs: Long): GpsFixResult = GpsFixResult.Unavailable
}
