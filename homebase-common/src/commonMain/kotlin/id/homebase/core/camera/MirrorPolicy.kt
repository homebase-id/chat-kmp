package id.homebase.core.camera

object MirrorPolicy {
    fun shouldMirror(lens: CameraLens, mirrorFront: Boolean): Boolean =
        lens == CameraLens.Front && mirrorFront
}
