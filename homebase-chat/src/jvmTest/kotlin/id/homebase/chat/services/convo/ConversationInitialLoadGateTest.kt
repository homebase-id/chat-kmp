package id.homebase.chat.services.convo

import id.homebase.api.common.OdinId
import id.homebase.api.sync.DriveState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ConversationInitialLoadGateTest {

    private val row = ConversationUiModel(
        id = Uuid.parse("11111111-1111-1111-1111-111111111111"),
        name = "alice",
        lastMessage = "hi",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(1_000L),
        unreadCount = 0,
        avatarInitials = "",
        avatarTiny = null,
        participants = listOf(OdinId("owner.test"), OdinId("alice.test")),
        lastRead = Instant.fromEpochMilliseconds(0L),
        admins = emptySet(),
        avatarModel = ConversationAvatarModel(
            type = ConversationAvatarModel.Type.Connection,
            odinId = OdinId("alice.test"),
        ),
    )

    @Test
    fun emptyDbBeforeSync_isNotReady() {
        val gate = InitialLoadGate()
        val data = gate.settle(gate.loaded(emptyList(), wasReady = false))
        assertFalse(data.dataReady)
        assertFalse(data.initialSyncFailed)
    }

    @Test
    fun emptyDb_syncCompletesWithZeroConversations_isReadyAndEmpty() {
        val gate = InitialLoadGate()
        val loaded = gate.loaded(emptyList(), wasReady = false)
        gate.syncRoundFinished(completed = true)
        val data = gate.settle(loaded)
        assertTrue(data.dataReady)
        assertFalse(data.initialSyncFailed)
        assertTrue(data.items.isEmpty())
    }

    @Test
    fun emptyDb_syncFails_isFailed_andRetryReturnsToLoadingThenRecovers() {
        val gate = InitialLoadGate()
        val loaded = gate.loaded(emptyList(), wasReady = false)
        gate.syncRoundFinished(completed = false)
        val failed = gate.settle(loaded)
        assertTrue(failed.dataReady)
        assertTrue(failed.initialSyncFailed)

        val retrying = gate.retry(failed)
        assertFalse(retrying.dataReady)
        assertFalse(retrying.initialSyncFailed)
        assertEquals(retrying, gate.settle(retrying))

        gate.syncRoundFinished(completed = true)
        val recovered = gate.settle(retrying)
        assertTrue(recovered.dataReady)
        assertFalse(recovered.initialSyncFailed)
    }

    @Test
    fun retryThatFailsAgain_isFailedAgain() {
        val gate = InitialLoadGate()
        val loaded = gate.loaded(emptyList(), wasReady = false)
        gate.syncRoundFinished(completed = false)
        val retrying = gate.retry(gate.settle(loaded))
        gate.syncRoundFinished(completed = false)
        assertTrue(gate.settle(retrying).initialSyncFailed)
    }

    @Test
    fun nonEmptyDb_isReadyImmediately_beforeAnySync() {
        val gate = InitialLoadGate()
        val data = gate.settle(gate.loaded(listOf(row), wasReady = false))
        assertTrue(data.dataReady)
        assertFalse(data.initialSyncFailed)
    }

    @Test
    fun failedSyncWithRows_isNotFailedState() {
        val gate = InitialLoadGate()
        gate.syncRoundFinished(completed = false)
        val data = gate.settle(gate.loaded(listOf(row), wasReady = false))
        assertTrue(data.dataReady)
        assertFalse(data.initialSyncFailed)
    }

    @Test
    fun syncFinishingBeforeTheDbRead_stillReadyAfterLoad() {
        val gate = InitialLoadGate()
        gate.syncRoundFinished(completed = true)
        val data = gate.loaded(emptyList(), wasReady = false)
        assertTrue(data.dataReady)
    }

    @Test
    fun syncRoundBeforeLoadCompletes_doesNotFlipReadyOnStaleData() {
        val gate = InitialLoadGate()
        gate.syncRoundFinished(completed = true)
        val untouched = ConversationsData(dataReady = false)
        assertEquals(untouched, gate.settle(untouched))
    }

    @Test
    fun laterSuccessClearsFailedFlag() {
        val gate = InitialLoadGate()
        val loaded = gate.loaded(emptyList(), wasReady = false)
        gate.syncRoundFinished(completed = false)
        val failed = gate.settle(loaded)
        gate.syncRoundFinished(completed = true)
        assertFalse(gate.settle(failed).initialSyncFailed)
    }

    @Test
    fun reset_returnsToPending() {
        val gate = InitialLoadGate()
        gate.syncRoundFinished(completed = true)
        gate.loaded(emptyList(), wasReady = false)
        gate.reset()
        assertFalse(gate.settle(gate.loaded(emptyList(), wasReady = false)).dataReady)
    }

    @Test
    fun startLaunchThrowing_endsInFailedState_notStuckLoading() = runTest {
        val gate = InitialLoadGate()
        var data = ConversationsData(dataReady = false)
        runGuardedInitialLoad(onFailure = {
            gate.loadFailed()
            data = gate.settle(data)
        }) { error("credentials not set") }
        assertTrue(data.dataReady)
        assertTrue(data.initialSyncFailed)
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        var failed = false
        assertFailsWith<CancellationException> {
            runGuardedInitialLoad(onFailure = { failed = true }) { throw CancellationException("cancelled") }
        }
        assertFalse(failed)
    }

    @Test
    fun syncCompletedBeforeSubscribe_seededDone_isReadyAndEmpty() {
        val gate = InitialLoadGate()
        gate.seed(DriveState.Completed(totalCount = 0))
        val data = gate.settle(gate.loaded(emptyList(), wasReady = false))
        assertTrue(data.dataReady)
        assertFalse(data.initialSyncFailed)
    }

    @Test
    fun syncFailedBeforeSubscribe_seededFailed_isFailed() {
        val gate = InitialLoadGate()
        gate.seed(DriveState.Failed("offline"))
        val data = gate.settle(gate.loaded(emptyList(), wasReady = false))
        assertTrue(data.initialSyncFailed)
    }

    @Test
    fun seedFromInFlightOrMissingDrive_staysPending() {
        val gate = InitialLoadGate()
        gate.seed(DriveState.Synchronizing())
        gate.seed(DriveState.Initialized)
        gate.seed(null)
        assertFalse(gate.settle(gate.loaded(emptyList(), wasReady = false)).dataReady)
    }

    @Test
    fun seedDoesNotOverrideAnObservedRound() {
        val gate = InitialLoadGate()
        gate.syncRoundFinished(completed = true)
        gate.seed(DriveState.Failed("stale"))
        assertFalse(gate.settle(gate.loaded(emptyList(), wasReady = false)).initialSyncFailed)
    }
}
