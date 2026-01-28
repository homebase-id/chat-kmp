package id.homebase.api.client.drives.files

import kotlinx.serialization.Serializable

/** New media file for upload. Note: payload uses ByteArray instead of File/Blob. */
@Serializable
data class NewMediaFile(
    val key: String? = null,
    val payload: ByteArray,
    val thumbnailKey: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as NewMediaFile

        if (key != other.key) return false
        if (!payload.contentEquals(other.payload)) return false
        if (thumbnailKey != other.thumbnailKey) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key?.hashCode() ?: 0
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (thumbnailKey?.hashCode() ?: 0)
        return result
    }
}


