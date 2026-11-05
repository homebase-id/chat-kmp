package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.fileshare.FileShareDescriptor
import id.homebase.core.filesync.FilesyncProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class FilesyncShareDescriptionTest {
    private val circleId = Uuid.random()
    private val sam = FilesyncAudience.Person(OdinId("sam.test"))

    private fun node(name: String, folder: Boolean) = FilesyncNode(Uuid.random(), FilesyncProtocol.RootNodeId, folder, name, modifiedMs = null, sizeBytes = null)

    private fun handle(fileType: Int, size: Long? = null) = FilesyncHandle(
        Uuid.random(), null, fileType, KeyHeader.newRandom16(),
        JsonObject(size?.let { mapOf("size" to JsonPrimitive(it)) } ?: emptyMap()), null,
    )

    @Test
    fun aFolderNamesTheShareAndOnlyFilesAreCounted() {
        val tree = listOf(handle(FilesyncProtocol.FolderFileType), handle(FilesyncProtocol.FileFileType, 100), handle(FilesyncProtocol.FileFileType, 50))

        val described = describe(listOf(node("zebra.txt", false), node("Photos", true)), tree, circleId, sam, FilesyncAccess.Edit, 1_900_000_000_000, " see you ")

        assertEquals(
            FileShareDescriptor(
                circleId = circleId.toString(), title = "Photos", titleIsFolder = true, files = 2, bytes = 150,
                access = FileShareDescriptor.ACCESS_EDIT, note = "see you", expiresAt = 1_900_000_000_000,
            ),
            described,
        )
    }

    @Test
    fun withoutAFolderTheFirstFileByNameNamesItAndAGroupIsNamed() {
        val hiking = FilesyncAudience.Group(circleId, "Hiking", listOf(OdinId("sam.test")))

        val described = describe(listOf(node("b.txt", false), node("A.txt", false)), listOf(handle(FilesyncProtocol.FileFileType)), circleId, hiking, FilesyncAccess.View, null, "  ")

        assertEquals("A.txt", described.title)
        assertEquals(false, described.titleIsFolder)
        assertEquals("Hiking", described.group)
        assertEquals(FileShareDescriptor.ACCESS_VIEW, described.access)
        assertEquals(null, described.note)
    }
}
