package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MessageClusterPositionTest {

    private val alice = OdinId("alice.test")
    private val bob = OdinId("bob.test")
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun msg(
        sender: OdinId = alice,
        offsetMinutes: Int = 0,
    ): MessageListContentModel.Message {
        val time = baseTime + offsetMinutes.minutes
        return MessageListContentModel.Message(
            message = MessageUiModel(
                id = kotlin.uuid.Uuid.random(),
                globalTransitId = null,
                fileId = kotlin.uuid.Uuid.random(),
                conversationId = kotlin.uuid.Uuid.random(),
                content = "test",
                userDate = time,
                modified = null,
                created = time,
                originalAuthor = sender,
                sender = sender,
                displayName = sender.domainName,
                localReadTimestamp = null,
                isDeleted = false,
                isPendingSend = false,
                versionTag = kotlin.uuid.Uuid.NIL,
                messageAppData = MessageAppData(),
                reactionPreview = null,
                previewThumbnail = null,
                payloads = null,
                keyHeader = KeyHeader.empty(),
                hasMore = false,
            )
        )
    }

    @Test
    fun singleMessage_isAlone() {
        val result = computeClusterPositions(listOf(msg()))
        assertEquals(MessageClusterPosition.ALONE, result[0].clusterPosition)
    }

    @Test
    fun twoMessages_sameSender_withinWindow_areStartAndEnd() {
        val messages = listOf(
            msg(sender = alice, offsetMinutes = 0),
            msg(sender = alice, offsetMinutes = 1),
        )
        val result = computeClusterPositions(messages)
        assertEquals(MessageClusterPosition.START, result[0].clusterPosition)
        assertEquals(MessageClusterPosition.END, result[1].clusterPosition)
    }

    @Test
    fun threeMessages_sameSender_withinWindow_haveMiddle() {
        val messages = listOf(
            msg(sender = alice, offsetMinutes = 0),
            msg(sender = alice, offsetMinutes = 1),
            msg(sender = alice, offsetMinutes = 2),
        )
        val result = computeClusterPositions(messages)
        assertEquals(MessageClusterPosition.START, result[0].clusterPosition)
        assertEquals(MessageClusterPosition.MIDDLE, result[1].clusterPosition)
        assertEquals(MessageClusterPosition.END, result[2].clusterPosition)
    }

    @Test
    fun differentSenders_allAlone() {
        val messages = listOf(
            msg(sender = alice, offsetMinutes = 0),
            msg(sender = bob, offsetMinutes = 1),
            msg(sender = alice, offsetMinutes = 2),
        )
        val result = computeClusterPositions(messages)
        assertEquals(MessageClusterPosition.ALONE, result[0].clusterPosition)
        assertEquals(MessageClusterPosition.ALONE, result[1].clusterPosition)
        assertEquals(MessageClusterPosition.ALONE, result[2].clusterPosition)
    }

    @Test
    fun sameSender_outsideTimeWindow_allAlone() {
        val messages = listOf(
            msg(sender = alice, offsetMinutes = 0),
            msg(sender = alice, offsetMinutes = 4),
        )
        val result = computeClusterPositions(messages)
        assertEquals(MessageClusterPosition.ALONE, result[0].clusterPosition)
        assertEquals(MessageClusterPosition.ALONE, result[1].clusterPosition)
    }

    @Test
    fun mixedClusters_correctPositions() {
        val messages = listOf(
            msg(sender = alice, offsetMinutes = 0),
            msg(sender = alice, offsetMinutes = 1),
            msg(sender = bob, offsetMinutes = 2),
            msg(sender = bob, offsetMinutes = 3),
            msg(sender = bob, offsetMinutes = 4),
            msg(sender = alice, offsetMinutes = 10),
        )
        val result = computeClusterPositions(messages)
        assertEquals(MessageClusterPosition.START, result[0].clusterPosition)
        assertEquals(MessageClusterPosition.END, result[1].clusterPosition)
        assertEquals(MessageClusterPosition.START, result[2].clusterPosition)
        assertEquals(MessageClusterPosition.MIDDLE, result[3].clusterPosition)
        assertEquals(MessageClusterPosition.END, result[4].clusterPosition)
        assertEquals(MessageClusterPosition.ALONE, result[5].clusterPosition)
    }

    @Test
    fun emptyList_returnsEmpty() {
        val result = computeClusterPositions(emptyList())
        assertEquals(0, result.size)
    }

    @Test
    fun exactlyAtThreeMinuteBoundary_breaksCluster() {
        val messages = listOf(
            msg(sender = alice, offsetMinutes = 0),
            msg(sender = alice, offsetMinutes = 3),
        )
        val result = computeClusterPositions(messages)
        assertEquals(MessageClusterPosition.ALONE, result[0].clusterPosition)
        assertEquals(MessageClusterPosition.ALONE, result[1].clusterPosition)
    }

    @Test
    fun justUnderThreeMinutes_staysClustered() {
        val almostThree = baseTime + 2.minutes + 59.seconds
        val messages = listOf(
            MessageListContentModel.Message(
                message = msgModel(sender = alice, time = baseTime)
            ),
            MessageListContentModel.Message(
                message = msgModel(sender = alice, time = almostThree)
            ),
        )
        val result = computeClusterPositions(messages)
        assertEquals(MessageClusterPosition.START, result[0].clusterPosition)
        assertEquals(MessageClusterPosition.END, result[1].clusterPosition)
    }

    @Test
    fun senderToReceiverTransition_breaksClusters() {
        val messages = listOf(
            msg(sender = alice, offsetMinutes = 0),
            msg(sender = alice, offsetMinutes = 1),
            msg(sender = bob, offsetMinutes = 1),
            msg(sender = bob, offsetMinutes = 2),
        )
        val result = computeClusterPositions(messages)
        assertEquals(MessageClusterPosition.START, result[0].clusterPosition)
        assertEquals(MessageClusterPosition.END, result[1].clusterPosition)
        assertEquals(MessageClusterPosition.START, result[2].clusterPosition)
        assertEquals(MessageClusterPosition.END, result[3].clusterPosition)
    }

    @Test
    fun rapidSenderReceiverAlternation_allAlone() {
        val messages = listOf(
            msg(sender = alice, offsetMinutes = 0),
            msg(sender = bob, offsetMinutes = 0),
            msg(sender = alice, offsetMinutes = 1),
            msg(sender = bob, offsetMinutes = 1),
        )
        val result = computeClusterPositions(messages)
        result.forEach { item ->
            assertEquals(MessageClusterPosition.ALONE, item.clusterPosition)
        }
    }

    @Test
    fun longClusterOfFiveMessages() {
        val messages = (0..4).map { i -> msg(sender = alice, offsetMinutes = i) }
        val result = computeClusterPositions(messages)
        assertEquals(MessageClusterPosition.START, result[0].clusterPosition)
        assertEquals(MessageClusterPosition.MIDDLE, result[1].clusterPosition)
        assertEquals(MessageClusterPosition.MIDDLE, result[2].clusterPosition)
        assertEquals(MessageClusterPosition.MIDDLE, result[3].clusterPosition)
        assertEquals(MessageClusterPosition.END, result[4].clusterPosition)
    }

    @Test
    fun timeGapMidCluster_splitsClusters() {
        val messages = listOf(
            msg(sender = alice, offsetMinutes = 0),
            msg(sender = alice, offsetMinutes = 1),
            msg(sender = alice, offsetMinutes = 5),
            msg(sender = alice, offsetMinutes = 6),
        )
        val result = computeClusterPositions(messages)
        assertEquals(MessageClusterPosition.START, result[0].clusterPosition)
        assertEquals(MessageClusterPosition.END, result[1].clusterPosition)
        assertEquals(MessageClusterPosition.START, result[2].clusterPosition)
        assertEquals(MessageClusterPosition.END, result[3].clusterPosition)
    }

    @Test
    fun preservesMessageData() {
        val original = msg(sender = alice, offsetMinutes = 0)
        val result = computeClusterPositions(listOf(original))
        assertEquals(original.message.id, result[0].message.id)
        assertEquals(original.message.content, result[0].message.content)
    }

    private fun msgModel(
        sender: OdinId = alice,
        time: Instant = baseTime,
    ) = MessageUiModel(
        id = kotlin.uuid.Uuid.random(),
        globalTransitId = null,
        fileId = kotlin.uuid.Uuid.random(),
        conversationId = kotlin.uuid.Uuid.random(),
        content = "test",
        userDate = time,
        modified = null,
        created = time,
        originalAuthor = sender,
        sender = sender,
        displayName = sender.domainName,
        localReadTimestamp = null,
        isDeleted = false,
        isPendingSend = false,
        versionTag = kotlin.uuid.Uuid.NIL,
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader.empty(),
        hasMore = false,
    )
}
