package id.homebase.chat.calling

enum class WebRtcBackend { WebRtcKmp, WebRtcJava, Unsupported }

internal expect val webRtcBackend: WebRtcBackend

internal expect fun webRtcCompileProof(): Any
