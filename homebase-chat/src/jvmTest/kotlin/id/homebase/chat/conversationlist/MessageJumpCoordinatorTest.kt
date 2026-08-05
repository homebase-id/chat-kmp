package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A pinned-bar tap / reply-quote tap on a message that has scrolled out of the
 * in-memory window used to fall through an `indexOfLast == -1` and do nothing.
 */
class MessageJumpCoordinatorTest {

    private val conversationId = Uuid.random()
    private val alice = OdinId("alice.test")
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun message(id: Uuid): MessageListContentModel.Message =
        MessageListContentModel.Message(
            message = MessageUiModel(
                id = id,
                globalTransitId = null,
                fileId = Uuid.random(),
                conversationId = conversationId,
                content = "test",
                userDate = baseTime,
                modified = null,
                created = baseTime,
                originalAuthor = alice,
                sender = alice,
                displayName = alice.domainName,
                localReadTimestamp = null,
                isDeleted = false,
                isPendingSend = false,
                versionTag = Uuid.NIL,
                messageAppData = MessageAppData(),
                reactionPreview = null,
                previewThumbnail = null,
                payloads = null,
                keyHeader = KeyHeader.empty(),
                hasMore = false,
            )
        )

    private class Harness(
        windowIds: List<Uuid>,
        private val onDisk: Set<Uuid> = windowIds.toSet(),
        private val excludedFromView: Set<Uuid> = emptySet(),
    ) {
        val window = windowIds.toMutableList()
        var loadAroundCalls = 0
            private set
        val reportedUnavailable = mutableListOf<Uuid>()
        val reportedExcluded = mutableListOf<Uuid>()

        fun coordinator(
            state: MutableStateFlow<MessageListUiState>,
            onLoadAround: (Uuid) -> Unit = {},
        ) = MessageJumpCoordinator(
            messagesUiState = state,
            isMessageInWindow = { _, messageId -> window.contains(messageId) },
            isExcludedFromView = { _, messageId ->
                window.contains(messageId) && excludedFromView.contains(messageId)
            },
            loadAroundMessage = { _, messageId ->
                loadAroundCalls++
                if (!onDisk.contains(messageId)) {
                    false
                } else {
                    window.add(messageId)
                    onLoadAround(messageId)
                    true
                }
            },
            reportUnavailable = { _, messageId -> reportedUnavailable.add(messageId) },
            reportExcluded = { _, messageId -> reportedExcluded.add(messageId) },
        )
    }

    private fun stateWith(items: List<MessageListContentModel>) =
        MutableStateFlow(MessageListUiState(messages = items.toPersistentList()))

    @Test
    fun inWindowTarget_scrollsAndHighlightsWithoutLoading() = runTest {
        val target = Uuid.random()
        val items = listOf(message(Uuid.random()), message(target), message(Uuid.random()))
        val state = stateWith(items)
        val harness = Harness(windowIds = items.map { it.message.id })
        val coordinator = harness.coordinator(state)

        coordinator.jumpToMessage(conversationId, target)

        assertEquals(0, harness.loadAroundCalls)
        val scroll = assertNotNull(state.value.scrollPosition)
        assertEquals(1, scroll.firstVisibleItemIndex)
        assertTrue(scroll.triggerScroll)
        assertEquals(target, state.value.highlightedMessageId)
        assertNull(coordinator.pendingTarget)
    }

    @Test
    fun outOfWindowTarget_loadsAroundThenResolvesOnNextEmission() = runTest {
        val target = Uuid.random()
        val loadedItems = listOf(message(Uuid.random()), message(target))
        val state = stateWith(listOf(message(Uuid.random())))
        val harness = Harness(
            windowIds = state.value.messages.map { (it as MessageListContentModel.Message).message.id },
            onDisk = setOf(target),
        )
        val coordinator = harness.coordinator(state)

        coordinator.jumpToMessage(conversationId, target)

        assertEquals(1, harness.loadAroundCalls)
        assertEquals(target, coordinator.pendingTarget)
        assertNull(state.value.scrollPosition)
        assertEquals(target, state.value.highlightedMessageId)

        assertEquals(
            JumpTargetResolution.Landed(1),
            coordinator.resolvePendingJump(loadedItems),
        )
        assertNull(coordinator.pendingTarget)
        assertTrue(harness.reportedUnavailable.isEmpty())
    }

    @Test
    fun outOfWindowTarget_staysArmedUntilTheTargetLands() = runTest {
        val target = Uuid.random()
        val state = stateWith(listOf(message(Uuid.random())))
        val harness = Harness(windowIds = emptyList(), onDisk = setOf(target))
        val coordinator = harness.coordinator(state)

        coordinator.jumpToMessage(conversationId, target)

        assertEquals(
            JumpTargetResolution.Pending,
            coordinator.resolvePendingJump(listOf(message(Uuid.random()))),
        )
        assertEquals(target, coordinator.pendingTarget)
    }

    @Test
    fun missingTarget_reportsUnavailableAndClearsTheJump() = runTest {
        val target = Uuid.random()
        val state = stateWith(listOf(message(Uuid.random())))
        val harness = Harness(windowIds = emptyList(), onDisk = emptySet())
        val coordinator = harness.coordinator(state)

        coordinator.jumpToMessage(conversationId, target)

        assertEquals(listOf(target), harness.reportedUnavailable)
        assertNull(coordinator.pendingTarget)
        assertNull(state.value.highlightedMessageId)
        assertNull(state.value.scrollPosition)
    }

    @Test
    fun targetExcludedByTheExitCutoff_disarmsAndReportsInsteadOfRetryingForever() = runTest {
        val target = Uuid.random()
        val rendered = listOf(message(Uuid.random()))
        val state = stateWith(rendered)
        val harness = Harness(
            windowIds = rendered.map { it.message.id } + target,
            excludedFromView = setOf(target),
        )
        val coordinator = harness.coordinator(state)

        coordinator.arm(conversationId, target)

        assertEquals(
            JumpTargetResolution.ExcludedFromView,
            coordinator.resolvePendingJump(rendered),
        )
        assertNull(coordinator.pendingTarget)
        assertEquals(listOf(target), harness.reportedExcluded)
        assertTrue(harness.reportedUnavailable.isEmpty())
    }

    @Test
    fun jumpToExcludedTarget_reportsWithoutArmingOrReseeding() = runTest {
        val target = Uuid.random()
        val state = stateWith(listOf(message(Uuid.random())))
        val harness = Harness(windowIds = listOf(target), excludedFromView = setOf(target))
        val coordinator = harness.coordinator(state)

        coordinator.jumpToMessage(conversationId, target)

        assertEquals(listOf(target), harness.reportedExcluded)
        assertNull(coordinator.pendingTarget)
        assertEquals(0, harness.loadAroundCalls)
        assertNull(state.value.scrollPosition)
        assertNull(state.value.highlightedMessageId)
    }

    @Test
    fun ensureWindowContains_skipsTheReseedWhenTheWindowAlreadyHoldsTheTarget() = runTest {
        val target = Uuid.random()
        val state = stateWith(listOf(message(target)))
        val harness = Harness(windowIds = listOf(target))
        val coordinator = harness.coordinator(state)

        assertTrue(coordinator.ensureWindowContains(conversationId, target))
        assertEquals(0, harness.loadAroundCalls)
    }
}
