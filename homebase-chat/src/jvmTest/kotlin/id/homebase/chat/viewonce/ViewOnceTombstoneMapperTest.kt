package id.homebase.chat.viewonce

import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.mapToMessageData
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ViewOnceTombstoneMapperTest {

    private val now = Clock.System.now().toEpochMilliseconds()
    private val thumb = """{"pixelWidth":4,"pixelHeight":4,"contentType":"image/jpeg","content":"AAAA"}"""

    @Test
    fun serverTombstoneOfAViewOnceMapsToAViewOnceModelWithNothingLeftToLoad() = runTest {
        val fileId = Uuid.random()
        val header = serverTombstone(fileId = fileId, createdMs = now - 2 * DAY_MS, updatedMs = now - DAY_MS)

        val model = assertNotNull(mapToMessageData(header, ownerCredentials()))

        val content = assertIs<MessageContent.ViewOnce>(model.messageContent)
        assertNull(content.descriptor)
        assertNull(model.payloads)
        assertNull(model.previewThumbnail)
        assertEquals(fileId, model.id)
        assertTrue(model.isDeleted)
    }

    @Test
    fun aLocalOptimisticTombstoneStillDropsThePayloadsAndThumbnail() = runTest {
        val header = viewOnceHeader(
            fileState = "deleted",
            createdMs = now - DAY_MS,
            updatedMs = now,
            previewThumbnailJson = thumb,
        )

        val model = assertNotNull(mapToMessageData(header, ownerCredentials()))

        assertIs<MessageContent.ViewOnce>(model.messageContent)
        assertNull(model.payloads)
        assertNull(model.previewThumbnail)
    }

    @Test
    fun theTombstoneRendersFromStateNotFromTheDescriptor() = runTest {
        val me = ownerCredentials().requireActiveDomain()
        val opened = mapToMessageData(
            serverTombstone(createdMs = now - 2 * DAY_MS, updatedMs = now - DAY_MS), ownerCredentials(),
        )!!
        val expired = mapToMessageData(
            serverTombstone(createdMs = now - 40 * DAY_MS, updatedMs = now - 5 * DAY_MS), ownerCredentials(),
        )!!

        assertEquals(ViewOnceState.Opened, ViewOnceRules.stateOf(opened, now, me))
        assertEquals(ViewOnceState.Expired, ViewOnceRules.stateOf(expired, now, me))
    }

    @Test
    fun aTombstoneOfAnotherKindMapsExactlyAsBefore() = runTest {
        val header = viewOnceHeader(
            fileState = "deleted",
            uniqueId = Uuid.random(),
            dataType = 0,
            createdMs = now - DAY_MS,
            updatedMs = now,
            content = "",
            previewThumbnailJson = thumb,
        )

        val model = assertNotNull(
            mapToMessageData(header, ownerCredentials(), displayNameResolver = { "Resolved Contact" }),
        )

        assertEquals(VO_SENDER, model.displayName)
        assertNull(model.messageContent)
        assertEquals("Deleted File", model.content)
        assertTrue(model.isDeleted)
        assertEquals(listOf("chat_web0"), model.payloads?.map { it.key })
        assertNotNull(model.previewThumbnail)
        assertEquals(header.fileMetadata.appData.uniqueId, model.id)
    }
}
