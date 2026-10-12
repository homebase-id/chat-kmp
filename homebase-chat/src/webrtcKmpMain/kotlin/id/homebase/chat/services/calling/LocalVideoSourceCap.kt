package id.homebase.chat.services.calling

/** Caps what the local camera source hands the encoder at [SendVideoCap]; must run before the first PeerConnection. */
internal expect fun capLocalVideoSource()
