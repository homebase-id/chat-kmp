package id.homebase.core.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.ui.platform.ClipEntry

actual fun clipEntryOf(string: String, sensitive: Boolean): ClipEntry {
    val clip = ClipData.newPlainText("Homebase", string)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras =
            PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    return ClipEntry(clip)
}
