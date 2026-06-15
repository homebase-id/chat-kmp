package id.homebase.core.lists.model

import id.homebase.api.common.OdinId
import id.homebase.core.lists.ListsProtocol
import kotlinx.serialization.Serializable

/**
 * Descriptor for a list (one [ListsProtocol.ListDefinitionFileType] file per list,
 * uniqueId == groupId == listId). Envelope facts (creator, timestamps) come from the
 * HomebaseFile (originalAuthor / created) — never duplicated here.
 *
 * @property title user-visible list name.
 * @property members source of truth for transit recipients; the creator is implied (read
 *   it from the file's originalAuthor). Serializes as domain strings via OdinIdSerializer.
 * @property colorOrEmoji optional accent (rendered richly later).
 * @property schemaVersion forward-compat marker.
 */
@Serializable
data class ListDefinition(
    val title: String,
    val members: List<OdinId> = emptyList(),
    val colorOrEmoji: String? = null,
    val schemaVersion: Int = 1,
) {
    fun isValid(): Boolean {
        if (title.isBlank()) return false
        if (title.codePointLength() > ListsProtocol.MaxTitleCodePoints) return false
        if ((colorOrEmoji?.codePointLength() ?: 0) > 16) return false
        if (schemaVersion < 1) return false
        return true
    }
}

/** UTF-16-safe code-point count (emoji are surrogate pairs). */
internal fun String.codePointLength(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        i += if (i + 1 < length && this[i].isHighSurrogate() && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}
