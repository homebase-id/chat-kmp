package id.homebase.core.moments.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 */
class MomentsVideoSession {
    private val _isMuted = MutableStateFlow(true)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    fun toggleMuted() {
        _isMuted.value = !_isMuted.value
    }
}
