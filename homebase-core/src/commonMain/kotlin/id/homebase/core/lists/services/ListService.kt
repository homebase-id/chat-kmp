package id.homebase.core.lists.services

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.core.lists.ListsProtocol
import id.homebase.core.lists.model.ListDefinition
import kotlin.uuid.Uuid

/** Create / rename / delete a list (the ListDefinition file). Membership add/remove is Phase 4. */
class ListService(
    private val writer: ListFileWriter,
    private val listStream: ListStream,
) {
    /** Create a list. members = the people to share with (creator is implied via originalAuthor). */
    suspend fun createList(title: String, members: List<OdinId> = emptyList()): Uuid {
        val listId = Uuid.random()
        val def = ListDefinition(
            title = title.truncateToCodePoints(ListsProtocol.MaxTitleCodePoints),
            members = members,
        )
        writer.createFile(
            uniqueId = listId,
            groupId = listId,
            fileType = ListsProtocol.ListDefinitionFileType,
            contentJson = OdinSystemSerializer.serialize(def),
            recipients = writer.recipientsExcludingSelf(members),
        )
        return listId
    }

    suspend fun renameList(listId: Uuid, newTitle: String? = null, newColorOrEmoji: String? = null) {
        val rec = listStream.lists.value.lists.find { it.listId == listId } ?: return
        val def = rec.definition.copy(
            title = (newTitle ?: rec.definition.title).truncateToCodePoints(ListsProtocol.MaxTitleCodePoints),
            colorOrEmoji = newColorOrEmoji ?: rec.definition.colorOrEmoji,
        )
        writer.updateFile(
            uniqueId = listId,
            groupId = listId,
            fileType = ListsProtocol.ListDefinitionFileType,
            contentJson = OdinSystemSerializer.serialize(def),
            versionTag = rec.versionTag,
            recipients = writer.recipientsExcludingSelf(rec.definition.members),
        )
    }

    suspend fun deleteList(listId: Uuid) {
        val rec = listStream.lists.value.lists.find { it.listId == listId } ?: return
        writer.deleteFile(
            fileId = rec.fileId,
            uniqueId = listId,
            recipients = writer.recipientsExcludingSelf(rec.definition.members),
        )
    }
}
