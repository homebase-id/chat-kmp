package id.homebase.chat.services.calling

/** Front and back cameras in OS enumeration order, ids matching webrtc-kmp's `MediaTrackSettings.deviceId`. */
expect fun platformCameras(): List<CameraInfo>
