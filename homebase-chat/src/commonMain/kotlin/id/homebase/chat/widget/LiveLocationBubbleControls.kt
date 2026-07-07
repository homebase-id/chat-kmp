package id.homebase.chat.widget

/**
 * Live-share controls attached to a location chat bubble. Present for every location message in a real
 * conversation (both sender and receiver); null only for the pre-send staging preview. The bubble's
 * LIVE/ENDED state is NOT carried here — it's read from the message's own descriptor
 * (`LocationPreviewDescriptor.liveShareUntilMs`). This only carries the side + the action callbacks.
 *
 * - [sentByYou] gates the start/stop links (you can only share/stop your own location message).
 * - [onStart]/[onStop] update this message's descriptor (set/clear the live window). The duration
 *   may be the `LIVE_SHARE_INDEFINITE` sentinel (indefinite pick from the menu, #1013).
 * - [onStartShareBack] shares YOUR live location from someone else's bubble — sends a new own
 *   message instead of touching theirs. Null duration = mirror the sender's live end-time
 *   (single tap on a LIVE bubble); non-null = picked from the menu on a fresh static bubble —
 *   either path may carry the indefinite sentinel.
 * - [onOpenMap] opens the Live Location map (used by either side when the share is live).
 */
data class LiveLocationBubbleControls(
    val sentByYou: Boolean,
    val onStart: (durationMs: Long) -> Unit,
    val onStop: () -> Unit,
    val onStartShareBack: (durationMs: Long?) -> Unit,
    val onOpenMap: () -> Unit,
    /**
     * When MY live share already fully covers this conversation: the absolute end of that
     * coverage, else null. While set and in the future, the bubble hides its "share live
     * location" offers — no invitation to start a share that's already running (#966 follow-up).
     */
    val ownShareUntilMs: Long? = null,
)
