package id.homebase.core.util

import android.content.Intent
import id.homebase.api.ActivityProvider

actual object InAppBrowser {
    actual fun open(url: String) {
        // Started from the Activity, without FLAG_ACTIVITY_NEW_TASK, so the browser lands in the
        // app's own task and Back returns here (#1089).
        val activity = ActivityProvider.getActivity() ?: return
        activity.startActivity(
            Intent(activity, InAppBrowserActivity::class.java)
                .putExtra(InAppBrowserActivity.EXTRA_URL, url)
        )
    }
}
