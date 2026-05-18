package id.homebase.api

import android.content.Context
import androidx.activity.ComponentActivity
import java.lang.ref.WeakReference

/**
 * Provides access to the current Android Activity and the application Context.
 * Activity access is per-instance (WeakReference, must be set in
 * Activity.onCreate); application Context is process-scoped and should be
 * set once in Application.onCreate so components that only need Context
 * (cacheDir, ContentResolver, etc.) don't have to wait for an Activity.
 * Instrumented tests can call [initializeApplicationContext] directly with
 * the test context — no Activity required.
 */
object ActivityProvider {
    private var activityRef: WeakReference<ComponentActivity>? = null
    private var appContext: Context? = null

    /**
     * Initialize with the current activity. Call in Activity.onCreate (and
     * Activity.onResume if you want the reference refreshed). Also caches
     * the application Context, so callers that only need Context don't need
     * a separate [initializeApplicationContext] call.
     */
    fun initialize(activity: ComponentActivity) {
        activityRef = WeakReference(activity)
        if (appContext == null) appContext = activity.applicationContext
    }

    /**
     * Initialize with the application Context only. Call in
     * Application.onCreate at process start so components that only need
     * Context can use [requireApplicationContext] before any Activity exists.
     */
    fun initializeApplicationContext(context: Context) {
        appContext = context.applicationContext ?: context
    }

    /** Get the current activity, or null if not available. */
    fun getActivity(): ComponentActivity? = activityRef?.get()

    /**
     * Get the current activity, throwing if not initialized.
     * @throws IllegalStateException if activity is not available
     */
    fun requireActivity(): ComponentActivity =
            activityRef?.get()
                    ?: throw kotlin.IllegalStateException(
                        "Activity not initialized. Call ActivityProvider.initialize(activity) in onCreate()"
                    )

    /**
     * Get the application Context, throwing if neither an Activity nor an
     * application Context has been registered.
     * @throws IllegalStateException if no Context is available
     */
    fun requireApplicationContext(): Context =
            appContext
                    ?: activityRef?.get()?.applicationContext
                    ?: throw kotlin.IllegalStateException(
                        "Application context not initialized. Call ActivityProvider.initializeApplicationContext(this) in Application.onCreate()"
                    )

    /** Clear all references. Call this in Activity.onDestroy() if needed. */
    fun clear() {
        activityRef = null
        appContext = null
    }
}
