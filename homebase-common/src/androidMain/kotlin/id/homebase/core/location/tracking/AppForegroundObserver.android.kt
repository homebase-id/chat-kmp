package id.homebase.core.location.tracking

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

actual fun observeAppForeground(onChange: (Boolean) -> Unit) {
    // Lifecycle registration must happen on the main thread; a headless
    // process start (boot receiver, background location wake) may call
    // through from elsewhere.
    Handler(Looper.getMainLooper()).post {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        onChange(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onChange(true)
            override fun onStop(owner: LifecycleOwner) = onChange(false)
        })
    }
}
