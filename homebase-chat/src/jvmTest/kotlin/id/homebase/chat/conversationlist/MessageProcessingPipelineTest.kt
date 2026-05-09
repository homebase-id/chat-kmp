package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Tests the message processing pipeline that was moved to `withContext(Dispatchers.Default)`
 * in the ANR fix. Verifies that filtering, sorting, grouping, clustering, and unread
 * separator insertion produce correct results when run on a background dispatcher.
 */
class MessageProcessingPipelineTest {

    private val alice = OdinId("alice.test")
    private val bob = OdinId("bob.test")
    private val conversationId = Uuid.random()
    // 2023-11-14T12:00:00Z — pinned to noon UTC so timezone-shifted offsets in tests
    // (up to ±12h) stay on the same calendar date. The previous value (22:13:20Z) made
    // `messagesOnSameDay_shareOneSection` fail in any TZ east of UTC+0:47 because
    // baseTime+60min crossed local midnight.
    private val baseTime = Instant.fromEpochMilliseconds(1_699_963_200_000L)

    private fun msg(
        sender: OdinId = alice,
        offset: Int = 0,
        isDeleted: Boolean = false,
        isStatusMessage: Boolean = false,
        content: String = "message-$offset",
    ): MessageUiModel = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = conversationId,
        content = content,
        userDate = baseTime + offset.minutes,
        modified = null,
        created = baseTime + offset.minutes,
        originalAuthor = sender,
        sender = sender,
        displayName = sender.domainName,
        localReadTimestamp = null,
        isDeleted = isDeleted,
        isPendingSend = false,
        versionTag = Uuid.NIL,
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader.empty(),
        hasMore = false,
        isStatusMessage = isStatusMessage,
    )

    /**
     * Replicates the pipeline from ConversationListViewModel.collect that was wrapped
     * in withContext(Dispatchers.Default). Pure function, no ViewModel dependency.
     */
    private fun processMessages(
        rawMessages: List<MessageUiModel>,
        conversationId: Uuid = this.conversationId,
        exitedAt: Instant? = null,
        isNoteToSelf: Boolean = false,
    ): List<MessageListContentModel> {
        val filteredByExit = if (exitedAt != null)
            rawMessages.filter { it.userDate <= exitedAt }
        else
            rawMessages
        val messages = if (isNoteToSelf)
            filteredByExit.filter { !it.isDeleted }
        else
            filteredByExit
        val timezone = TimeZone.currentSystemDefault()
        val groupedMessages = messages.sortedBy { it.userDate }.groupBy { message ->
            message.userDate.toLocalDateTime(timezone).date
        }
        val models: MutableList<MessageListContentModel> =
            mutableListOf(MessageListContentModel.Header)

        var systemIndex = 0
        models.addAll(groupedMessages.flatMap { (date, messages) ->
            val sectionHeader = listOf(MessageListContentModel.Section(date))
            val items = messages.map { msg ->
                if (msg.isStatusMessage)
                    MessageListContentModel.System(msg.content, msg.userDate, systemIndex++)
                else
                    MessageListContentModel.Message(msg)
            }
            val messageItems = items.filterIsInstance<MessageListContentModel.Message>()
            val clustered = computeClusterPositions(messageItems)
            val clusteredMap = clustered.associateBy { it.message.id }
            sectionHeader + items.map { item ->
                if (item is MessageListContentModel.Message)
                    clusteredMap[item.message.id] ?: item
                else
                    item
            }
        })
        return models
    }

    // --- Basic pipeline behavior ---

    @Test
    fun emptyMessageList_producesOnlyHeader() {
        val result = processMessages(emptyList())
        assertEquals(1, result.size)
        assertIs<MessageListContentModel.Header>(result[0])
    }

    @Test
    fun singleMessage_producesHeaderSectionAndMessage() {
        val result = processMessages(listOf(msg()))
        assertEquals(3, result.size)
        assertIs<MessageListContentModel.Header>(result[0])
        assertIs<MessageListContentModel.Section>(result[1])
        assertIs<MessageListContentModel.Message>(result[2])
    }

    @Test
    fun messagesAreSortedByUserDate() {
        val messages = listOf(
            msg(offset = 30, content = "third"),
            msg(offset = 10, content = "first"),
            msg(offset = 20, content = "second"),
        )
        val result = processMessages(messages)
        val messageItems = result.filterIsInstance<MessageListContentModel.Message>()
        assertEquals("first", messageItems[0].message.content)
        assertEquals("second", messageItems[1].message.content)
        assertEquals("third", messageItems[2].message.content)
    }

    // --- Day grouping ---

    @Test
    fun messagesOnSameDay_shareOneSection() {
        val messages = listOf(
            msg(offset = 0),
            msg(offset = 30),
            msg(offset = 60),
        )
        val result = processMessages(messages)
        val sections = result.filterIsInstance<MessageListContentModel.Section>()
        assertEquals(1, sections.size)
    }

    @Test
    fun messagesOnDifferentDays_getSeparateSections() {
        val messages = listOf(
            msg(offset = 0),
            msg(offset = 24 * 60), // 24 hours later
        )
        val result = processMessages(messages)
        val sections = result.filterIsInstance<MessageListContentModel.Section>()
        assertEquals(2, sections.size)
    }

    @Test
    fun sectionDatesAreCorrect() {
        val timezone = TimeZone.currentSystemDefault()
        val day1Time = baseTime
        val day2Time = baseTime + 25.hours

        val messages = listOf(msg(offset = 0), msg(offset = 25 * 60))
        val result = processMessages(messages)
        val sections = result.filterIsInstance<MessageListContentModel.Section>()

        val expectedDay1 = day1Time.toLocalDateTime(timezone).date
        val expectedDay2 = day2Time.toLocalDateTime(timezone).date
        assertEquals(expectedDay1, sections[0].date)
        assertEquals(expectedDay2, sections[1].date)
    }

    // --- Clustering integration ---

    @Test
    fun clusteringAppliedWithinSections() {
        val messages = listOf(
            msg(sender = alice, offset = 0),
            msg(sender = alice, offset = 1),
            msg(sender = alice, offset = 2),
        )
        val result = processMessages(messages)
        val messageItems = result.filterIsInstance<MessageListContentModel.Message>()
        assertEquals(MessageClusterPosition.START, messageItems[0].clusterPosition)
        assertEquals(MessageClusterPosition.MIDDLE, messageItems[1].clusterPosition)
        assertEquals(MessageClusterPosition.END, messageItems[2].clusterPosition)
    }

    @Test
    fun differentSenders_noClustering() {
        val messages = listOf(
            msg(sender = alice, offset = 0),
            msg(sender = bob, offset = 1),
            msg(sender = alice, offset = 2),
        )
        val result = processMessages(messages)
        val messageItems = result.filterIsInstance<MessageListContentModel.Message>()
        messageItems.forEach {
            assertEquals(MessageClusterPosition.ALONE, it.clusterPosition)
        }
    }

    // --- Status messages ---

    @Test
    fun statusMessages_renderedAsSystem() {
        val messages = listOf(
            msg(offset = 0, isStatusMessage = true, content = "Alice joined"),
            msg(offset = 1, content = "Hello"),
        )
        val result = processMessages(messages)
        val systems = result.filterIsInstance<MessageListContentModel.System>()
        assertEquals(1, systems.size)
        assertEquals("Alice joined", systems[0].text)
    }

    @Test
    fun statusMessagesDoNotAffectClustering() {
        val messages = listOf(
            msg(sender = alice, offset = 0),
            msg(sender = alice, offset = 1, isStatusMessage = true, content = "status"),
            msg(sender = alice, offset = 2),
        )
        val result = processMessages(messages)
        val messageItems = result.filterIsInstance<MessageListContentModel.Message>()
        // Only 2 message items (status message is a System, not Message)
        assertEquals(2, messageItems.size)
        assertEquals(MessageClusterPosition.START, messageItems[0].clusterPosition)
        assertEquals(MessageClusterPosition.END, messageItems[1].clusterPosition)
    }

    // --- Exit filter ---

    @Test
    fun exitedAt_filtersMessagesAfterExitTime() {
        val messages = listOf(
            msg(offset = 0, content = "before"),
            msg(offset = 10, content = "after"),
        )
        val exitTime = baseTime + 5.minutes
        val result = processMessages(messages, exitedAt = exitTime)
        val messageItems = result.filterIsInstance<MessageListContentModel.Message>()
        assertEquals(1, messageItems.size)
        assertEquals("before", messageItems[0].message.content)
    }

    // --- Note to Self deletion filter ---

    @Test
    fun noteToSelf_hidesDeletedMessages() {
        val messages = listOf(
            msg(offset = 0, content = "visible"),
            msg(offset = 1, isDeleted = true, content = "deleted"),
        )
        val result = processMessages(messages, isNoteToSelf = true)
        val messageItems = result.filterIsInstance<MessageListContentModel.Message>()
        assertEquals(1, messageItems.size)
        assertEquals("visible", messageItems[0].message.content)
    }

    @Test
    fun regularConversation_showsDeletedMessages() {
        val messages = listOf(
            msg(offset = 0, content = "visible"),
            msg(offset = 1, isDeleted = true, content = "deleted"),
        )
        val result = processMessages(messages, isNoteToSelf = false)
        val messageItems = result.filterIsInstance<MessageListContentModel.Message>()
        assertEquals(2, messageItems.size)
    }

    // --- Background dispatcher correctness ---

    @Test
    fun pipelineProducesSameResultOnDefaultDispatcher() = runTest {
        val messages = (0 until 100).map { i ->
            msg(
                sender = if (i % 3 == 0) alice else bob,
                offset = i * 2,
                isStatusMessage = i % 10 == 0,
                content = "msg-$i"
            )
        }

        val mainResult = processMessages(messages)
        val backgroundResult = withContext(Dispatchers.Default) {
            processMessages(messages)
        }

        assertEquals(mainResult.size, backgroundResult.size)
        mainResult.zip(backgroundResult).forEachIndexed { index, (main, bg) ->
            assertEquals(
                main::class,
                bg::class,
                "Item type mismatch at index $index"
            )
            when (main) {
                is MessageListContentModel.Message -> {
                    val bgMsg = bg as MessageListContentModel.Message
                    assertEquals(main.message.id, bgMsg.message.id)
                    assertEquals(main.clusterPosition, bgMsg.clusterPosition)
                }
                is MessageListContentModel.Section -> {
                    assertEquals(main.date, (bg as MessageListContentModel.Section).date)
                }
                is MessageListContentModel.System -> {
                    assertEquals(main.text, (bg as MessageListContentModel.System).text)
                }
                else -> {}
            }
        }
    }

    // --- Scale test ---

    @Test
    fun largeMessageSet_processesCorrectly() {
        val messages = (0 until 1000).map { i ->
            msg(
                sender = if (i % 2 == 0) alice else bob,
                offset = i,
                content = "msg-$i"
            )
        }
        val result = processMessages(messages)
        val messageItems = result.filterIsInstance<MessageListContentModel.Message>()
        assertEquals(1000, messageItems.size)
        // Verify sorted order
        for (i in 1 until messageItems.size) {
            assertTrue(
                messageItems[i].message.userDate >= messageItems[i - 1].message.userDate,
                "Messages should be sorted by userDate"
            )
        }
    }

    @Test
    fun largeMessageSet_producesCorrectOnBackgroundDispatcher() = runTest {
        val messages = (0 until 1000).map { i ->
            msg(sender = alice, offset = i, content = "msg-$i")
        }
        val result = withContext(Dispatchers.Default) {
            processMessages(messages)
        }
        val messageItems = result.filterIsInstance<MessageListContentModel.Message>()
        assertEquals(1000, messageItems.size)

        // First and last should be START/END of a big cluster
        assertEquals(MessageClusterPosition.START, messageItems.first().clusterPosition)
        assertEquals(MessageClusterPosition.END, messageItems.last().clusterPosition)
    }

    // --- Interleaved types ---

    @Test
    fun mixedMessageAndStatusTypes_correctOrdering() {
        val messages = listOf(
            msg(sender = alice, offset = 0, content = "hello"),
            msg(sender = alice, offset = 1, isStatusMessage = true, content = "Alice changed topic"),
            msg(sender = bob, offset = 2, content = "hey"),
            msg(sender = bob, offset = 3, content = "what's up"),
        )
        val result = processMessages(messages)

        // Header, Section, then items in order
        assertIs<MessageListContentModel.Header>(result[0])
        assertIs<MessageListContentModel.Section>(result[1])
        assertIs<MessageListContentModel.Message>(result[2])
        assertIs<MessageListContentModel.System>(result[3])
        assertIs<MessageListContentModel.Message>(result[4])
        assertIs<MessageListContentModel.Message>(result[5])

        // Bob's two messages should be clustered
        val bobFirst = result[4] as MessageListContentModel.Message
        val bobSecond = result[5] as MessageListContentModel.Message
        assertEquals(MessageClusterPosition.START, bobFirst.clusterPosition)
        assertEquals(MessageClusterPosition.END, bobSecond.clusterPosition)
    }
}
