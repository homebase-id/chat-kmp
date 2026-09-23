package id.homebase.core.ui.screens.card

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UpdateFileResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class CardDesignPublishTest {

    private val anonymous = AccessControlList(requiredSecurityGroup = "anonymous")

    private fun theme(
        data: String,
        priority: Int = 1000,
        versionTag: Uuid = Uuid.random(),
        tags: List<Uuid> = listOf(THEME_ATTRIBUTE_TYPE),
    ) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = SystemDriveConstants.homePageConfigDrive.alias,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.empty(),
        fileMetadata = FileMetadata(
            versionTag = versionTag,
            payloads = listOf(PayloadDescriptor(key = "headr_key", lastModified = 1L)),
            appData = AppFileMetaData(
                tags = tags,
                fileType = 77,
                groupId = Uuid.random(),
                content = """{"type":"8f7eb1c32fc72c0abf0cee09be588f26","priority":$priority,"data":$data}""",
            ),
        ),
        serverMetadata = ServerMetadata(accessControlList = anonymous, allowDistribution = true),
    )

    private class FakeThemeFiles(var files: List<HomebaseFile>, var conflicts: Int = 0) : CardThemeFiles {
        val updates = mutableListOf<UpdateFileByFileIdRequest>()
        var queries = 0

        override suspend fun query(): List<HomebaseFile> {
            queries++
            return files
        }

        override suspend fun update(request: UpdateFileByFileIdRequest): UpdateFileResult? {
            updates += request
            if (conflicts-- > 0) return null
            return UpdateFileResult(fileId = request.fileId, driveId = request.driveId, newVersionTag = Uuid.random())
        }
    }

    private fun UpdateFileByFileIdRequest.content(): JsonObject =
        cardJson.parseToJsonElement(metadata.appData.content!!).jsonObject

    @Test
    fun writesTheDesignIntoTheThemeOnceKeepingEveryOtherKey() = runTest {
        val file = theme("""{"themeId":"555","tagLine":"Ring-bearer","headerImageKey":"headr_key","cardDesign":"board"}""")
        val files = FakeThemeFiles(listOf(file))

        assertEquals(CardDesignPublish.Published, publishCardDesign(CardDesign.DOSSIER, files))

        val update = files.updates.single()
        assertEquals(file.fileId, update.fileId)
        assertEquals(SystemDriveConstants.homePageConfigDrive.alias, update.driveId)
        assertEquals(file.fileMetadata.versionTag, update.metadata.versionTag)
        val content = update.content()
        assertEquals(JsonPrimitive(1000), content["priority"])
        assertEquals(JsonPrimitive("8f7eb1c32fc72c0abf0cee09be588f26"), content["type"])
        val data = content["data"]!!.jsonObject
        assertEquals(
            mapOf(
                "themeId" to "555",
                "tagLine" to "Ring-bearer",
                "headerImageKey" to "headr_key",
                "cardDesign" to CardDesign.DOSSIER,
            ),
            data.mapValues { (it.value as JsonPrimitive).content },
        )
    }

    @Test
    fun theHeaderIsRewrittenAsIsWithoutTouchingPayloads() = runTest {
        val file = theme("""{"themeId":"777"}""")
        val files = FakeThemeFiles(listOf(file))

        publishCardDesign(CardDesign.POSTER, files)

        val update = files.updates.single()
        assertTrue(update.payloads.isNullOrEmpty())
        assertTrue(update.thumbnails.isNullOrEmpty())
        assertTrue(update.instructions.manifest.payloadDescriptors.isNullOrEmpty())
        assertFalse(update.metadata.isEncrypted)
        assertNull(update.keyHeader)
        assertEquals(anonymous, update.metadata.accessControlList)
        assertTrue(update.metadata.allowDistribution)
        val appData = file.fileMetadata.appData
        assertEquals(appData.tags, update.metadata.appData.tags)
        assertEquals(appData.fileType, update.metadata.appData.fileType)
        assertEquals(appData.groupId, update.metadata.appData.groupId)
    }

    @Test
    fun theHighestPriorityThemeIsTheOneWritten() = runTest {
        val low = theme("""{"themeId":"555"}""", priority = 2000)
        val high = theme("""{"themeId":"777"}""", priority = 10)
        val other = theme("""{"status":"x"}""", priority = 1, tags = listOf(Uuid.random()))
        val files = FakeThemeFiles(listOf(low, other, high))

        publishCardDesign(CardDesign.BOARD, files)

        assertEquals(high.fileId, files.updates.single().fileId)
    }

    @Test
    fun aStaleVersionTagRereadsAndRemergesTheCurrentTheme() = runTest {
        val stale = theme("""{"themeId":"555"}""")
        val current = theme("""{"themeId":"555","tagLine":"Edited meanwhile"}""")
        val files = FakeThemeFiles(listOf(stale), conflicts = 1)
        val publish = object : CardThemeFiles by files {
            override suspend fun update(request: UpdateFileByFileIdRequest): UpdateFileResult? =
                files.update(request).also { if (it == null) files.files = listOf(current) }
        }

        assertEquals(CardDesignPublish.Published, publishCardDesign(CardDesign.COLLAGE, publish))

        assertEquals(2, files.queries)
        val retry = files.updates.last()
        assertEquals(current.fileMetadata.versionTag, retry.metadata.versionTag)
        assertEquals(JsonPrimitive("Edited meanwhile"), retry.content()["data"]!!.jsonObject["tagLine"])
    }

    @Test
    fun endlessConflictsGiveUp() = runTest {
        val files = FakeThemeFiles(listOf(theme("""{"themeId":"555"}""")), conflicts = Int.MAX_VALUE)

        assertFailsWith<IllegalStateException> { publishCardDesign(CardDesign.POSTER, files) }
        assertEquals(3, files.updates.size)
    }

    @Test
    fun noThemeMeansNoWrite() = runTest {
        val none = FakeThemeFiles(emptyList())
        assertEquals(CardDesignPublish.NoTheme, publishCardDesign(CardDesign.POSTER, none))
        assertTrue(none.updates.isEmpty())

        val unreadable = FakeThemeFiles(listOf(theme("1").copy(fileMetadata = FileMetadata(appData = AppFileMetaData(tags = listOf(THEME_ATTRIBUTE_TYPE), content = "not json")))))
        assertEquals(CardDesignPublish.NoTheme, publishCardDesign(CardDesign.POSTER, unreadable))
        assertTrue(unreadable.updates.isEmpty())
    }
}
