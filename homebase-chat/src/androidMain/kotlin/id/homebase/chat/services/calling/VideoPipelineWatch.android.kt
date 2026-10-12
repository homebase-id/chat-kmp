package id.homebase.chat.services.calling

// Camera2 and MediaCodec already report freezes and codec errors to logcat with the WebRTC tags.
internal actual fun watchVideoPipeline(): () -> Unit = {}
