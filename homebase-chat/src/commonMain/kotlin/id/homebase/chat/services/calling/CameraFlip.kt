package id.homebase.chat.services.calling

data class CameraInfo(val id: String, val facing: CameraFacing)

/**
 * The camera a flip should switch to: the first camera, in platform enumeration order, facing
 * the other way from [currentId]. Null when [currentId] is not listed or nothing faces the
 * other way. Enumeration order matters: Android lists the primary back and front cameras before
 * auxiliary lenses (depth, macro, wide), and an auxiliary lens may deliver no frames at all.
 */
fun flipTarget(currentId: String?, cameras: List<CameraInfo>): String? {
    val current = cameras.firstOrNull { it.id == currentId } ?: return null
    return cameras.firstOrNull { it.facing != current.facing }?.id
}
