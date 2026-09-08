@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.audio

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSError

/**
 * The one place that touches the process-wide `AVAudioSession` category.
 *
 * `AVAudioSessionCategoryRecord` has no output route: a session left there by a
 * finished voice memo silently kills every later `AVPlayer` in the process — the
 * media decodes fine but the audio unit refuses to start, and AVPlayer posts
 * `FailedToPlayToEndTime(CoreMediaErrorDomain -66637)` while stuck at t=0.
 */
object AudioSession {

    fun configureForPlayback(): Boolean = activate(AVAudioSessionCategoryPlayback)

    fun configureForRecording(): Boolean = activate(AVAudioSessionCategoryRecord)

    /**
     * For callers that only need the route to be output-capable. Deliberately does
     * nothing when it already is, so a muted inline video never interrupts whatever
     * the user is listening to.
     */
    fun ensurePlaybackCapable() {
        val category = AVAudioSession.sharedInstance().category
        if (category != AVAudioSessionCategoryRecord) {
            Logger.d(tag = TAG) { "category=$category — output-capable, left alone" }
            return
        }
        Logger.w(tag = TAG) { "category=Record — moving to Playback so the player can output" }
        activate(AVAudioSessionCategoryPlayback)
    }

    /** Drop the route (and the Record category) once a recording is done with it. */
    fun releaseAfterRecording() = memScoped {
        val session = AVAudioSession.sharedInstance()
        val err = alloc<ObjCObjectVar<NSError?>>()
        session.setActive(
            false,
            AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            err.ptr,
        )
        err.value?.let { Logger.w(tag = TAG) { "setActive(false) failed: ${it.localizedDescription}" } }
        err.value = null
        // Category, not activation, is what breaks later playback — reset it even when
        // deactivation failed (another app holding the route, an in-flight interruption).
        session.setCategory(AVAudioSessionCategoryPlayback, err.ptr)
        err.value?.let { Logger.e(tag = TAG) { "reset to Playback failed: ${it.localizedDescription}" } }
    }

    private fun activate(category: String?): Boolean = memScoped {
        val session = AVAudioSession.sharedInstance()
        val err = alloc<ObjCObjectVar<NSError?>>()

        session.setCategory(category, err.ptr)
        err.value?.let {
            Logger.e(tag = TAG) { "setCategory($category) failed: ${it.localizedDescription}" }
            return@memScoped false
        }

        session.setActive(true, err.ptr)
        err.value?.let {
            Logger.e(tag = TAG) { "setActive(true) for $category failed: ${it.localizedDescription}" }
            return@memScoped false
        }

        true
    }

    private const val TAG = "AudioSession"
}
