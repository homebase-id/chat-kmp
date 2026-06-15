package id.homebase.core.lists.services

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.core.lists.ListsProtocol
import id.homebase.core.lists.model.ListItem
import id.homebase.core.lists.model.ListSortKeys
import kotlin.uuid.Uuid

/** Add / edit / check / reorder / delete list items (ListItem files, fileType 9101). */
class ListItemSenderService(
    private val writer: ListFileWriter,
    private val listStream: ListStream,
) {
    private fun membersOf(listId: Uuid): List<OdinId> =
        listStream.lists.value.lists.find { it.listId == listId }?.definition?.members ?: emptyList()

    private fun itemRecord(listId: Uuid, itemId: Uuid): ListItemRecord? =
        listStream.itemsByList.value[listId]?.find { it.itemId == itemId }

    /** Append a new item to the end of the list. Returns the new itemId. */
    suspend fun addItem(listId: Uuid, body: String): Uuid {
        val itemId = Uuid.random()
        val lastKey = listStream.itemsByList.value[listId]?.lastOrNull()?.item?.sortKey
        val sortKey = if (lastKey == null) ListSortKeys.first() else ListSortKeys.after(lastKey)
        val item = ListItem(
            body = body.truncateToCodePoints(ListsProtocol.MaxItemBodyCodePoints),
            sortKey = sortKey,
        )
        writer.createFile(
            uniqueId = itemId,
            groupId = listId,
            fileType = ListsProtocol.ListItemFileType,
            contentJson = OdinSystemSerializer.serialize(item),
            recipients = writer.recipientsExcludingSelf(membersOf(listId)),
        )
        return itemId
    }

    private suspend fun updateItem(listId: Uuid, itemId: Uuid, transform: (ListItem) -> ListItem) {
        val rec = itemRecord(listId, itemId) ?: return
        val updated = transform(rec.item)
        writer.updateFile(
            uniqueId = itemId,
            groupId = listId,
            fileType = ListsProtocol.ListItemFileType,
            contentJson = OdinSystemSerializer.serialize(updated),
            versionTag = rec.versionTag,
            recipients = writer.recipientsExcludingSelf(membersOf(listId)),
        )
    }

    suspend fun editItem(listId: Uuid, itemId: Uuid, newBody: String) =
        updateItem(listId, itemId) {
            it.copy(body = newBody.truncateToCodePoints(ListsProtocol.MaxItemBodyCodePoints))
        }

    suspend fun setChecked(listId: Uuid, itemId: Uuid, checked: Boolean) {
        // writer.selfDomain() is suspend → resolve it here, NOT inside the non-suspend copy lambda.
        val checkedBy: OdinId? = if (checked) writer.selfDomain() else null
        updateItem(listId, itemId) { it.copy(checked = checked, checkedByOdinId = checkedBy) }
    }

    suspend fun reorderItem(listId: Uuid, itemId: Uuid, newSortKey: String) =
        updateItem(listId, itemId) { it.copy(sortKey = newSortKey) }

    suspend fun deleteItem(listId: Uuid, itemId: Uuid) {
        val rec = itemRecord(listId, itemId) ?: return
        writer.deleteFile(
            fileId = rec.fileId,
            uniqueId = itemId,
            recipients = writer.recipientsExcludingSelf(membersOf(listId)),
        )
    }
}
