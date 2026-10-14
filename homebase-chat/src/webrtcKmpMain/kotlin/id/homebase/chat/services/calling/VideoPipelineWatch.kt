package id.homebase.chat.services.calling

/**
 * Logs what the OS and the native WebRTC stack say about the local video pipeline (camera session stops and
 * interruptions, encoder errors) into homebase.log for the life of a call; the returned function stops it.
 * [onCameraInterrupted] fires when the OS takes the running camera away (iOS backgrounding without multitasking camera
 * access, another app claiming it) and again when it hands it back; a platform that cannot tell never calls it.
 */
internal expect fun watchVideoPipeline(onCameraInterrupted: (interrupted: Boolean, detail: String) -> Unit): () -> Unit
