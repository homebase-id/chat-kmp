package id.homebase.core.location.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger

/**
 * Fused-provider PendingIntent registrations survive process death but NOT a
 * reboot. This receiver exists solely so BOOT_COMPLETED starts the app process:
 * MainApplication.onCreate then runs LocationTrackingCoordinator.onProcessStart(),
 * which re-arms the registration when tracking is enabled. The body itself has
 * nothing to do.
 */
class LocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Logger.withTag("LocationBootReceiver").i {
            "Boot completed — process started; coordinator re-arms tracking if enabled"
        }
    }
}
