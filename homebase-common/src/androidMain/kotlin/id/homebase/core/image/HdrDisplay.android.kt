package id.homebase.core.image

import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.runtime.Composable
import coil3.BitmapImage
import coil3.Image
import id.homebase.core.util.WindowHoldEffect
import id.homebase.core.util.WindowHolds

internal actual fun carriesGainmap(image: Image): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && (image as? BitmapImage)?.bitmap?.hasGainmap() == true

private val hdrHolds = WindowHolds(
    set = { colorMode.also { colorMode = ActivityInfo.COLOR_MODE_HDR } },
    restore = { modeBefore -> colorMode = modeBefore },
)

@Composable
internal actual fun HdrWindowEffect(active: Boolean) = WindowHoldEffect(hdrHolds, active)
