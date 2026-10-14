package id.homebase.chat.services.calling

internal data class CaptureFormat(
    val width: Int,
    val height: Int,
    val maxFps: Double,
    val pixelFormat: String,
    val binned: Boolean,
)

private val eightBitBiPlanar = setOf("420v", "420f")

/**
 * Telegram's rule (tgcalls VideoCapturerInterfaceImpl.mm): 8-bit 4:2:0 formats reaching 30 fps, exactly the send cap
 * if offered, else the smallest whose sides both reach its short side; binned and lowest max fps break ties.
 */
internal fun chooseCaptureFormat(formats: List<CaptureFormat>): CaptureFormat? {
    val usable = formats.filter { it.pixelFormat in eightBitBiPlanar && it.maxFps >= SendVideoCap.FPS }
    val cheapest = compareBy<CaptureFormat>({ !it.binned }, { it.maxFps })
    usable.filter { it.width == SendVideoCap.WIDTH && it.height == SendVideoCap.HEIGHT }
        .minWithOrNull(cheapest)
        ?.let { return it }
    val shortSide = minOf(SendVideoCap.WIDTH, SendVideoCap.HEIGHT)
    return usable.filter { it.width >= shortSide && it.height >= shortSide }
        .minWithOrNull(compareBy<CaptureFormat> { it.width * it.height }.then(cheapest))
}
