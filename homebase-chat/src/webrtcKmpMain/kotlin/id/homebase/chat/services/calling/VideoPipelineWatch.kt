package id.homebase.chat.services.calling

/**
 * Logs what the OS and the native WebRTC stack say about the local video pipeline (camera session stops and
 * interruptions, encoder errors) into homebase.log for the life of a call; the returned function stops it.
 */
internal expect fun watchVideoPipeline(): () -> Unit
