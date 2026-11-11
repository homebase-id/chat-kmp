package id.homebase.photobase.backup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat

class AndroidBackupDeviceProbe(private val context: Context) : BackupDeviceProbe {

    override suspend fun state(): DeviceState {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val power = context.getSystemService(PowerManager::class.java)
        return DeviceState(
            hasNetwork = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            isUnmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            isBatteryLow = level >= 0 && scale > 0 && level * 100 / scale < LOW_BATTERY_PERCENT,
            isLowPowerMode = power.isPowerSaveMode,
            freeStorageBytes = StatFs(context.filesDir.path).availableBytes,
            isOverheated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
        )
    }

    override fun libraryAccess(): LibraryAccess {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when {
                granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> LibraryAccess.Full
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> LibraryAccess.Limited
                else -> LibraryAccess.None
            }
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> LibraryAccess.Full
            else -> LibraryAccess.None
        }
    }

    override fun photoLocationsWithheld(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED

    private companion object {
        const val LOW_BATTERY_PERCENT = 15
    }
}
