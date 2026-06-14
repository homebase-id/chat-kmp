package id.homebase.core.location.tracking

actual fun createDeviceSensors(): DeviceSensors = NoDeviceSensors

/** Desktop is a viewer — no battery/step capture. */
private object NoDeviceSensors : DeviceSensors {
    override suspend fun batteryPercent(): Int? = null
    override suspend fun stepsSince(prevPointTimeMs: Long?, lastCumulative: Long?): StepSample =
        StepSample(null, null)
}
