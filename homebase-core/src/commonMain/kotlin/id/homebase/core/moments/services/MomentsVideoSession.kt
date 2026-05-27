package id.homebase.core.moments.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.Uuid

/**
 * App-session-scoped video preferences for the moments feed. Lives as a Koin
 * singleton so the user's mute choice survives navigating away from the feed
 * and back — without it, [MomentsFeedList] would reset to muted on every
 * recomposition tied to a new ViewModel instance.
 *
 * Scope: session only. There's deliberately no persistence to disk — the
 * default on every fresh app launch is "muted," matching Instagram /
 * TikTok / X behaviour. Once the user unmutes a single tile, every other
 * autoplaying tile follows for the rest of the session.
 *
 * Also tracks the last-known playback position of the currently-playing
 * inline video so that tapping into the moment detail picks up where the
 * feed left off (and vice versa). See [savePlaybackPosition] /
 * [readPlaybackPosition].
 */
@OptIn(ExperimentalTime::class)
class MomentsVideoSession {
    private val _isMuted = MutableStateFlow(true)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _playbackHandoff = MutableStateFlow<PlaybackHandoff?>(null)
    /**
     * Latest captured playback position for an inline video. Observed by
     * `MomentDetailScreen` on entry to decide whether the detail should
     * open already playing instead of paused-at-zero.
     */
    val playbackHandoff: StateFlow<PlaybackHandoff?> = _playbackHandoff.asStateFlow()

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    fun toggleMuted() {
        _isMuted.value = !_isMuted.value
    }

    /**
     * Record that the inline player for [fileId] / [payloadKey] is at
     * [positionMs] in the timeline. Called periodically while the video is
     * playing — the latest value wins. The session only holds the most
     * recent handoff; swiping the carousel to a different payload
     * overwrites the previous one.
     */
    fun savePlaybackPosition(fileId: Uuid, payloadKey: String, positionMs: Long) {
        _playbackHandoff.value = PlaybackHandoff(
            fileId = fileId,
            payloadKey = payloadKey,
            positionMs = positionMs,
            capturedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
    }

    /**
     * Return the stored playback position for [fileId] / [payloadKey] if
     * the handoff matches and was captured within [maxAgeMs] (default 60 s).
     * Stale or non-matching entries return `null`; callers should treat
     * `null` as "start from zero, paused".
     *
     * Reads do NOT consume — the same handoff can be read by the detail
     * screen on entry and again by the feed when the user navigates back,
     * so the carousel resumes from wherever the detail left it.
     */
    fun readPlaybackPosition(
        fileId: Uuid,
        payloadKey: String,
        maxAgeMs: Long = HandoffMaxAgeMs,
    ): Long? {
        val handoff = _playbackHandoff.value ?: return null
        if (handoff.fileId != fileId || handoff.payloadKey != payloadKey) return null
        val ageMs = Clock.System.now().toEpochMilliseconds() - handoff.capturedAtEpochMs
        if (ageMs > maxAgeMs) return null
        return handoff.positionMs
    }

    companion object {
        /**
         * 60 seconds. A quick feed→detail (or detail→feed) navigation lands
         * well under this. Coming back to the same video after a minute of
         * doing something else feels stale, so we restart from zero.
         */
        const val HandoffMaxAgeMs: Long = 60_000L
    }
}

data class PlaybackHandoff(
    val fileId: Uuid,
    val payloadKey: String,
    val positionMs: Long,
    val capturedAtEpochMs: Long,
)
