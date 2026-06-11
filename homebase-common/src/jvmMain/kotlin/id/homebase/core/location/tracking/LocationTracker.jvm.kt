package id.homebase.core.location.tracking

actual fun createLocationTracker(sink: LocationPointSink): LocationTracker =
    UnavailableLocationTracker

/** Desktop has no GPS — the Location screen shows the unavailable state. */
private object UnavailableLocationTracker : LocationTracker {
    override val isAvailable: Boolean = false
    override fun start(mode: TrackingMode) {}
    override fun setMode(mode: TrackingMode) {}
    override fun stop() {}
}
