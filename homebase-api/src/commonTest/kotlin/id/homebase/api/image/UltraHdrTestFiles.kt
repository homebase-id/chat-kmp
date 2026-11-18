package id.homebase.api.image

/** A gain map as its own JPEG, and how to apply it. */
internal class Gainmap(val jpeg: ByteArray, val metadata: GainmapMetadata)

/** [jpeg]'s gain map, read whole from memory, or null when it has none. */
internal fun UltraHdr.gainmapOf(jpeg: ByteArray): Gainmap? {
    val range = gainmapRange(jpeg) ?: return null
    if (range.last >= jpeg.size) return null
    val gainmap = jpeg.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
    return metadataOf(gainmap)?.let { Gainmap(gainmap, it) }
}
