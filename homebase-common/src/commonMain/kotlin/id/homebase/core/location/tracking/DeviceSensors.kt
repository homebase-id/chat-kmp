package id.homebase.core.location.tracking

/**
 * One pedometer read: the step [deltaSteps] over the queried window, plus the
 * platform's running [cumulative] count when it has one (Android = since-boot;
 * iOS answers intervals directly and returns null). The store persists
 * [cumulative] so the next delta can be computed across process death.
 */
data class StepSample(val deltaSteps: Int?, val cumulative: Long?)

/**
 * Cheap, hardware-backed device readings attached to GPS points by
 * `LocationPointStore`. Battery is free and permission-less; steps need the
 * activity/motion runtime permission and degrade to null when denied.
 */
interface DeviceSensors {
    /** Battery charge 0..100, or null if unavailable (desktop/web, simulator). */
    suspend fun batteryPercent(): Int?

    /**
     * Steps since [prevPointTimeMs] (the previous accepted point's time).
     * [lastCumulative] is the store's persisted running count (Android uses it
     * to subtract; iOS ignores it and queries the interval directly).
     */
    suspend fun stepsSince(prevPointTimeMs: Long?, lastCumulative: Long?): StepSample

    /**
     * Whether the OS battery saver / low-power mode is on. Cheap, synchronous, permission-less.
     * Drives the location power policy (#878 follow-up): on-demand fixes go cache-only (no radio)
     * and background uploads defer while saver is on. False where the OS has no such concept.
     */
    fun isPowerSaveMode(): Boolean
}

expect fun createDeviceSensors(): DeviceSensors
