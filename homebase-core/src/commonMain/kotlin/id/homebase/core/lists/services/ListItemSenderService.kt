package id.homebase.core.lists.services

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.core.lists.ListsProtocol
import id.homebase.core.lists.model.ListItem
import id.homebase.core.lists.model.ListSortKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Add / edit / check / reorder / delete list items (ListItem files, fileType 9101). */
class ListItemSenderService(
    private val writer: ListFileWriter,
    private val listStream: ListStream,
) {
    // Serializes appends and remembers the last sort key we issued per list, so two rapid
    // addItem calls get distinct increasing keys even before the optimistic write has been
    // merged back into listStream.itemsByList (which updates asynchronously via BatchReceived).
    private val addMutex = Mutex()
    private val lastIssuedSortKey = mutableMapOf<Uuid, String>()

    private fun membersOf(listId: Uuid): List<OdinId> =
        listStream.lists.value.lists.find { it.listId == listId }?.definition?.members ?: emptyList()

    private fun itemRecord(listId: Uuid, itemId: Uuid): ListItemRecord? =
        listStream.itemsByList.value[listId]?.find { it.itemId == itemId }

    /** Append a new item to the end of the list. Returns the new itemId. */
    suspend fun addItem(listId: Uuid, body: String): Uuid = addMutex.withLock {
        val itemId = Uuid.random()
        val streamLast = listStream.itemsByList.value[listId]?.lastOrNull()?.item?.sortKey
        // High-water mark of the highest key known, whether from the stream or our own pending adds.
        val lastKey = listOfNotNull(streamLast, lastIssuedSortKey[listId]).maxOrNull()
        val sortKey = if (lastKey == null) ListSortKeys.first() else ListSortKeys.after(lastKey)
        lastIssuedSortKey[listId] = sortKey
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
        itemId
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
