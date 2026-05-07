package id.homebase.core.vault

import android.view.WindowManager
import id.homebase.api.ActivityProvider

actual fun applyWindowPrivacy(active: Boolean) {
    val window = ActivityProvider.getActivity()?.window ?: return
    if (active) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

actual val needsComposePrivacyOverlay: Boolean = false
