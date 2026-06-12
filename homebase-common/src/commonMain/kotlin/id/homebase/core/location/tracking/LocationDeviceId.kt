package id.homebase.core.location.tracking

import id.homebase.api.storage.SharedPreferences
import kotlin.uuid.Uuid

/**
 * Stable per-install device identifier used to key this device's location
 * track files (one file per device per UTC hour), so multiple devices of the
 * same identity can track concurrently without write conflicts.
 *
 * Stored in [SharedPreferences] (not the encrypted keyValue store) on purpose:
 * it is identity-independent and must survive logout — after a re-login the
 * device keeps appending to its own files instead of forking a second set.
 */
class LocationDeviceId {

    val value: Uuid by lazy {
        SharedPreferences.getString(KEY)?.let { stored ->
            runCatching { Uuid.parse(stored) }.getOrNull()
        } ?: Uuid.random().also { SharedPreferences.putString(KEY, it.toString()) }
    }

    private companion object {
        const val KEY = "location_device_id"
    }
}
