package id.homebase.chat.services.calling

// webrtc-kmp's Android capturer already opens the camera at its 1280x720 default.
internal actual fun capLocalVideoSource() = Unit
