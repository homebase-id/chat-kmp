package id.homebase.core.auth

import androidx.activity.ComponentActivity
import java.lang.ref.WeakReference

/**
 * Provides access to the current Android Activity without using static singletons.
 *
 * Uses WeakReference to avoid memory leaks. Must be initialized in Activity.onCreate() and
 * preferably updated in Activity.onResume().
 */
object ActivityProvider {
    private var activityRef: WeakReference<ComponentActivity>? = null

    /**
     * Initialize with the current activity. Call this in Activity.onCreate() and
     * Activity.onResume().
     */
    fun initialize(activity: ComponentActivity) {
        activityRef = WeakReference(activity)
    }

    /** Get the current activity, or null if not available. */
    fun getActivity(): ComponentActivity? = activityRef?.get()

    /**
     * Get the current activity, throwing if not initialized.
     * @throws IllegalStateException if activity is not available
     */
    fun requireActivity(): ComponentActivity =
        activityRef?.get()
            ?: throw IllegalStateException(
                "Activity not initialized. Call ActivityProvider.initialize(activity) in onCreate()"
            )

    /** Clear the activity reference. Call this in Activity.onDestroy() if needed. */
    fun clear() {
        activityRef = null
    }
}
