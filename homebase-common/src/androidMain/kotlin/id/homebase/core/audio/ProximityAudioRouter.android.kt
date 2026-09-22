package id.homebase.core.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import co.touchlab.kermit.Logger
import id.homebase.api.ActivityProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun getProximityAudioRouter(): ProximityAudioRouter = AndroidProximityAudioRouter

private object AndroidProximityAudioRouter : ProximityAudioRouter, SensorEventListener {
    private val logger = Logger.withTag(TAG)

    private val _isNearEar = MutableStateFlow(false)
    override val isNearEar: StateFlow<Boolean> = _isNearEar.asStateFlow()

    private var running = false
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var routedToEarpiece = false
    private var previousMode: Int? = null
    private var previousSpeakerphoneOn = false

    private val context: Context get() = ActivityProvider.requireApplicationContext()
    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Synchronized
    override fun start() {
        if (running) return

        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (sensors == null || sensor == null) {
            logger.d { "No proximity sensor — raise-to-ear disabled" }
            return
        }

        running = true
        sensorManager = sensors
        proximitySensor = sensor
        acquireWakeLock()

        val registered = try {
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (t: Throwable) {
            logger.w(t) { "registerListener failed" }
            false
        }
        if (!registered) stop()
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        try {
            sensorManager?.unregisterListener(this)
            restoreLoudspeaker()
        } finally {
            releaseWakeLock()
            sensorManager = null
            proximitySensor = null
            _isNearEar.value = false
        }
    }

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val sensor = proximitySensor ?: return
        val reading = event.values.firstOrNull() ?: return

        // Most proximity sensors are binary and report only 0 or maximumRange, so the sensor's
        // own range is the threshold — a fixed centimetre value misreads them.
        val near = reading < sensor.maximumRange
        _isNearEar.value = near
        if (near) routeToEarpiece() else restoreLoudspeaker()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun routeToEarpiece() {
        if (routedToEarpiece) return
        val audio = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val earpiece = audio.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (earpiece == null || !audio.setCommunicationDevice(earpiece)) {
                logger.w { "Could not select the built-in earpiece" }
                return
            }
        } else {
            previousMode = audio.mode
            previousSpeakerphoneOn = audio.isSpeakerphoneOn
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = false
        }
        routedToEarpiece = true
    }

    @Suppress("DEPRECATION")
    private fun restoreLoudspeaker() {
        if (!routedToEarpiece) return
        routedToEarpiece = false
        val audio = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            audio.isSpeakerphoneOn = previousSpeakerphoneOn
            audio.mode = previousMode ?: AudioManager.MODE_NORMAL
            previousMode = null
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            logger.d { "PROXIMITY_SCREEN_OFF_WAKE_LOCK unsupported — no screen blanking" }
            return
        }
        wakeLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        val held = wakeLock ?: return
        wakeLock = null
        try {
            if (held.isHeld) held.release()
        } catch (t: Throwable) {
            // A leaked proximity lock keeps the user's screen off — surface it, never swallow it.
            logger.e(t) { "Proximity wake lock release failed" }
        }
    }

    private const val TAG = "ProximityAudioRouter"
    private const val WAKE_LOCK_TAG = "homebase:proximity-audio"
}
