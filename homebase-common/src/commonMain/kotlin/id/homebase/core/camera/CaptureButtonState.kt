package id.homebase.core.camera

// State machine derived from Signal-Android hud/CaptureButtonState.kt (AGPL-3.0, see NOTICE).
enum class CaptureButtonState {
    Photo,
    Video,
    RecordingHeld,
    RecordingLocked;

    val isRecording: Boolean get() = this == RecordingHeld || this == RecordingLocked

    val tapAction: CaptureAction?
        get() = when (this) {
            Photo -> CaptureAction.TakePhoto
            Video -> CaptureAction.StartLockedRecording
            RecordingLocked -> CaptureAction.StopRecording
            RecordingHeld -> null
        }

    fun longPressAction(holdToRecordAllowed: Boolean): CaptureAction? =
        if (!isRecording && holdToRecordAllowed) CaptureAction.StartHeldRecording else null

    val releaseAction: CaptureAction?
        get() = if (this == RecordingHeld) CaptureAction.StopRecording else null

    companion object {
        fun of(mode: CaptureMode, isRecording: Boolean, isRecordingLocked: Boolean): CaptureButtonState = when {
            isRecording && isRecordingLocked -> RecordingLocked
            isRecording -> RecordingHeld
            mode == CaptureMode.Video -> Video
            else -> Photo
        }

        /** Holding from photo mode needs a video use case already bound next to the photo one. */
        fun holdToRecordAllowed(modes: CameraModes, supportsSimultaneousVideo: Boolean): Boolean =
            modes.allows(CaptureMode.Video) && supportsSimultaneousVideo
    }
}

enum class CaptureAction { TakePhoto, StartLockedRecording, StartHeldRecording, StopRecording }
