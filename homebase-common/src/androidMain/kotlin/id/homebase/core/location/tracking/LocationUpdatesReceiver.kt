package id.homebase.core.location.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import com.google.android.gms.location.LocationResult
import id.homebase.api.coroutines.supervisedScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext

/**
 * Receives batched fused-location deliveries from the background PendingIntent
 * registration (see AndroidLocationTracker). Manifest-registered, so a delivery
 * wakes the app process if it is dead; MainApplication.onCreate has already
 * initialized SecureStorage + DatabaseManager + Koin by the time onReceive runs
 * (the same guarantee DriveFcmService relies on), so points go straight into
 * the encrypted buffer table.
 */
class LocationUpdatesReceiver : BroadcastReceiver(), KoinComponent {

    private val logger = Logger.withTag("LocationUpdatesReceiver")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOCATION_UPDATE) return
        // Defensive: a secondary process (none today) would have no Koin.
        if (GlobalContext.getOrNull() == null) {
            logger.w { "Koin not started — dropping location batch" }
            return
        }
        val result = LocationResult.extractResult(intent) ?: return
        val points = result.locations.map { it.toRawPoint(fg = false) }
        if (points.isEmpty()) return

        logger.d { "Background batch: ${points.size} points" }
        val pendingResult = goAsync()
        scope.launch {
            try {
                get<LocationPointStore>().submit(points)
                // Opportunistic drain — we're awake anyway; the coordinator
                // forwards to the uploader's rate-gated flushIfDue.
                get<LocationTrackingCoordinator>().onBackgroundPointsDelivered()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOCATION_UPDATE = "id.homebase.core.location.ACTION_LOCATION_UPDATE"

        // Receiver instances are throwaway; the scope must outlive them until
        // pendingResult.finish() — process-scoped like the work it performs.
        private val scope = supervisedScope("LocationUpdatesReceiver")
    }
}
