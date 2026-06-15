package id.homebase.core.lists.model

import id.homebase.api.common.OdinId
import id.homebase.core.lists.ListsProtocol
import kotlinx.serialization.Serializable

/**
 * Descriptor for one list item (one [ListsProtocol.ListItemFileType] file, uniqueId == itemId,
 * groupId == listId). Header-only. Body is markdown stored as a plain String. Envelope facts
 * come from the HomebaseFile.
 */
@Serializable
data class ListItem(
    val body: String,
    val checked: Boolean = false,
    val checkedByOdinId: OdinId? = null,
    val sortKey: String,
    val schemaVersion: Int = 1,
) {
    fun isValid(): Boolean {
        if (body.isBlank()) return false
        if (body.codePointLength() > ListsProtocol.MaxItemBodyCodePoints) return false
        if (sortKey.isBlank()) return false
        if (!checked && checkedByOdinId != null) return false
        if (schemaVersion < 1) return false
        return true
    }
}
