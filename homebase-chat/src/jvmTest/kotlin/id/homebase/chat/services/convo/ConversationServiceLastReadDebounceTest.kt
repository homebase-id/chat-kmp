package id.homebase.chat.services.convo

import id.homebase.api.common.time.UnixTimeUtc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Verifies the lastRead writeback path on [ConversationService] after the
 * pending-writeback state moved onto the in-memory conversation list (each
 * ConversationUiModel carries its own `lastRead` + `dirty`):
 *   - The setter ([ConversationService.updateLocalLastReadTime]) eagerly upserts
 *     ChatReadCount and marks the conversation dirty — but only when the
 *     candidate is a genuine advance (the entity's `resolveLastReadAdvance` gate).
 *   - The flush walks the dirty conversations, stamps each with the value the
 *     list holds, enqueues, and compare-and-clears the dirty flag.
 *
 * **Test seam.** The fake `ConversationParticipantLookup.advancedLastRead`
 * override runs the same entity transition the real ConversationStream does
 * (move lastRead + set dirty), minus the unreadCount DB re-derive. So calling
 * the setter advances the in-memory model exactly as production would; tests
 * also seed starting state directly via the fake's `setLastRead`.
 *
 * **Why `runBlocking` and not `runTest`.** The debounce is a
 * `scope.launch { delay(N); flushDirtyLastRead() }`; under `runTest`'s
 * StandardTestDispatcher launched coroutines interleave with the test body and
 * slip past `cancel()`, contaminating per-flush counters. `runBlocking` gives a
 * real scope + wall-clock `delay`, and the fixture passes `Long.MAX_VALUE / 2`
 * as the debounce so the timer never fires — every flush comes through
 * [ConversationService.flushLastReadNow].
 *
 * Stamp counting: every stamp bumps the conv-file's `fileMetadata.updated` by
 * exactly 1 ms (`OptimisticWriter.stampConversationLocalAppData`).
 */
class ConversationServiceLastReadDebounceTest {

    private fun newServiceScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun flushStampsDirtyConversationAndClearsTheFlag() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                val initialUpdatedMs = fixture.getConversationFile(convoId)!!
                    .fileMetadata.updated.milliseconds
                val initialOutboxRows = fixture.outboxRowCount()

                // A dirty conversation whose in-memory lastRead is 5000 — what
                // advancedLastRead would have left after a local mark-read.
                fixture.participantLookup.setLastRead(
                    convoId,
                    Instant.fromEpochMilliseconds(5_000L),
                    latestMessageTimestamp = Instant.fromEpochMilliseconds(10_000L),
                    dirty = true,
                )

                service.flushLastReadNow()

                assertEquals(
                    1L,
                    fixture.getConversationFile(convoId)!!.fileMetadata.updated.milliseconds -
                        initialUpdatedMs,
                    "flush must stamp the dirty conversation exactly once",
                )
                assertEquals(
                    initialOutboxRows + 1,
                    fixture.outboxRowCount(),
                    "flush must enqueue exactly one outbox row",
                )
                assertTrue(
                    fixture.participantLookup.getDirtyConversationIds().isEmpty(),
                    "flush must clear the dirty flag after a successful push",
                )
                assertTrue(
                    fixture.getConversationFile(convoId)!!
                        .fileMetadata.localAppData?.content?.contains("\"lastReadTime\":5000") == true,
                    "stamped conv-file must carry the model's lastRead (5000)",
                )
                assertTrue(
                    fixture.getConversationFile(convoId)!!
                        .fileMetadata.localAppData?.content?.contains("\"latestMessageTimestamp\":10000") == true,
                    "stamped conv-file must carry the model's latestMessageTimestamp (10000) — the list sort key ride-along",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun basicMapPrefersPersistedLatestMessageTimestampElseFallsBackToCreated() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val mapper = ConversationMapper(fixture.credentialsManager, fixture.dbm)
                val convoId = fixture.seedOneOnOne(other = "dave.test")

                // Fallback: a never-stamped conversation has no persisted sort key,
                // so mapToBasic seeds latestMessageTimestamp from fileMetadata.created.
                val freshFile = fixture.getConversationFile(convoId)!!
                assertEquals(
                    freshFile.fileMetadata.created.toInstant(),
                    mapper.mapToBasic(freshFile).latestMessageTimestamp,
                    "never-stamped conversation falls back to fileMetadata.created",
                )

                // Round-trip: a lastRead flush stamps the sort key into localAppData;
                // mapToBasic then reads it back instead of using created.
                fixture.participantLookup.setLastRead(
                    convoId,
                    Instant.fromEpochMilliseconds(5_000L),
                    latestMessageTimestamp = Instant.fromEpochMilliseconds(10_000L),
                    dirty = true,
                )
                service.flushLastReadNow()

                assertEquals(
                    10_000L,
                    mapper.mapToBasic(fixture.getConversationFile(convoId)!!)
                        .latestMessageTimestamp.toEpochMilliseconds(),
                    "mapToBasic must prefer the persisted localAppData sort key over created",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun flushStampsEachDirtyConversationOnce() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoA = fixture.seedOneOnOne(other = "alice.test")
                val convoB = fixture.seedOneOnOne(other = "bob.test")
                val convoC = fixture.seedOneOnOne(other = "carol.test")

                val initials = listOf(convoA, convoB, convoC).associateWith {
                    fixture.getConversationFile(it)!!.fileMetadata.updated.milliseconds
                }
                val initialOutboxRows = fixture.outboxRowCount()

                listOf(convoA, convoB, convoC).forEach { id ->
                    fixture.participantLookup.setLastRead(
                        id,
                        Instant.fromEpochMilliseconds(5_000L),
                        latestMessageTimestamp = Instant.fromEpochMilliseconds(10_000L),
                        dirty = true,
                    )
                }

                service.flushLastReadNow()

                listOf(convoA, convoB, convoC).forEach { id ->
                    val delta = fixture.getConversationFile(id)!!.fileMetadata.updated.milliseconds -
                        initials.getValue(id)
                    assertEquals(1L, delta, "each dirty convo stamped once; convo=$id delta=$delta")
                }
                assertEquals(
                    initialOutboxRows + 3,
                    fixture.outboxRowCount(),
                    "three dirty conversations → three outbox rows",
                )
                assertTrue(fixture.participantLookup.getDirtyConversationIds().isEmpty())
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun flushSkipsCleanConversations() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                val initialUpdatedMs = fixture.getConversationFile(convoId)!!
                    .fileMetadata.updated.milliseconds
                val initialOutboxRows = fixture.outboxRowCount()

                // Present in memory but not dirty — flush must ignore it.
                fixture.participantLookup.setLastRead(
                    convoId,
                    Instant.fromEpochMilliseconds(5_000L),
                    dirty = false,
                )

                service.flushLastReadNow()

                assertEquals(
                    initialUpdatedMs,
                    fixture.getConversationFile(convoId)!!.fileMetadata.updated.milliseconds,
                    "clean conversation must not be stamped",
                )
                assertEquals(
                    initialOutboxRows,
                    fixture.outboxRowCount(),
                    "clean conversation must not enqueue",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun setterUpsertsChatReadCountOnAdvance() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                // lastRead behind the latest message → an advance to 5000 counts.
                fixture.participantLookup.setLastRead(
                    convoId,
                    Instant.fromEpochMilliseconds(1_000L),
                    latestMessageTimestamp = Instant.fromEpochMilliseconds(10_000L),
                )

                service.updateLocalLastReadTime(convoId, UnixTimeUtc(5_000L))

                assertEquals(
                    5_000L,
                    fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId),
                    "the setter must eagerly upsert ChatReadCount on a genuine advance",
                )
                // The setter also advances the in-memory model (lastRead + dirty
                // together) by calling participantLookup.advancedLastRead.
                val advanced = fixture.participantLookup.getConversationById(convoId)!!
                assertEquals(
                    5_000L,
                    advanced.lastRead.toEpochMilliseconds(),
                    "the setter advances the in-memory lastRead",
                )
                assertTrue(advanced.dirty, "the setter marks the conversation dirty")
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun setterDoesNotUpsertOnNonAdvance() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                fixture.participantLookup.setLastRead(
                    convoId,
                    Instant.fromEpochMilliseconds(5_000L),
                    latestMessageTimestamp = Instant.fromEpochMilliseconds(10_000L),
                )

                // Candidate is behind the current lastRead — gate rejects.
                service.updateLocalLastReadTime(convoId, UnixTimeUtc(4_999L))

                assertNull(
                    fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId),
                    "a non-advancing candidate must not upsert ChatReadCount",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun setterDoesNotUpsertWhenSaturated() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                // lastRead == latestMessageTimestamp → already read everything.
                fixture.participantLookup.setLastRead(
                    convoId,
                    Instant.fromEpochMilliseconds(5_000L),
                    latestMessageTimestamp = Instant.fromEpochMilliseconds(5_000L),
                )

                service.updateLocalLastReadTime(convoId, UnixTimeUtc(7_000L))

                assertNull(
                    fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId),
                    "a saturated conversation must not upsert ChatReadCount",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun gateUsesInMemoryNotChatReadCount() = runBlocking {
        // Regression: the setter's gate reads the in-memory model's lastRead,
        // not ChatReadCount. A peer advance that landed in memory (t=3000) but
        // hasn't been mirrored into ChatReadCount (still t=1000) must still
        // reject a local advance to t=2000 — otherwise we'd advance + later
        // stamp the conv-file backwards.
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                fixture.dbm.chatReadCount.upsertLastReadTime(convoId, UnixTimeUtc(1_000L))
                fixture.participantLookup.setLastRead(
                    convoId,
                    Instant.fromEpochMilliseconds(3_000L),
                    latestMessageTimestamp = Instant.fromEpochMilliseconds(10_000L),
                )

                service.updateLocalLastReadTime(convoId, UnixTimeUtc(2_000L))

                assertEquals(
                    1_000L,
                    fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId),
                    "candidate 2000 <= in-memory 3000 → gate rejects, ChatReadCount unchanged",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun burstOfAdvancesFlushesToOneStampOfTheLatestValue() = runBlocking {
        // End-to-end of the production flow within the isolated test: the setter
        // gates + upserts ChatReadCount and advances the in-memory model
        // (lastRead + dirty, via participantLookup.advancedLastRead), and one
        // flush stamps the final value and clears the flag.
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                val initialUpdatedMs = fixture.getConversationFile(convoId)!!
                    .fileMetadata.updated.milliseconds
                val initialOutboxRows = fixture.outboxRowCount()

                fixture.participantLookup.setLastRead(
                    convoId,
                    Instant.fromEpochMilliseconds(0L),
                    latestMessageTimestamp = Instant.fromEpochMilliseconds(10_000L),
                )

                // Several rapid advances — the setter upserts ChatReadCount and
                // advances the in-memory model each time (dirty stays a single
                // bool), landing at 1004.
                repeat(5) { i ->
                    service.updateLocalLastReadTime(convoId, UnixTimeUtc(1_000L + i.toLong()))
                }

                assertEquals(
                    listOf(convoId),
                    fixture.participantLookup.getDirtyConversationIds(),
                    "burst of advances collapses to one dirty conversation",
                )

                service.flushLastReadNow()

                assertEquals(
                    1L,
                    fixture.getConversationFile(convoId)!!.fileMetadata.updated.milliseconds -
                        initialUpdatedMs,
                    "burst flushes to exactly one stamp",
                )
                assertEquals(
                    initialOutboxRows + 1,
                    fixture.outboxRowCount(),
                    "burst flushes to exactly one outbox row",
                )
                assertTrue(
                    fixture.getConversationFile(convoId)!!
                        .fileMetadata.localAppData?.content?.contains("\"lastReadTime\":1004") == true,
                    "stamped value must be the advanced model lastRead (1004)",
                )
                assertTrue(
                    fixture.participantLookup.getDirtyConversationIds().isEmpty(),
                    "flush clears the dirty flag",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun flushLastReadNow_isNoOpWhenNothingDirty() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")
                val initialUpdatedMs = fixture.getConversationFile(convoId)!!
                    .fileMetadata.updated.milliseconds
                val initialOutboxRows = fixture.outboxRowCount()

                service.flushLastReadNow()
                service.flushLastReadNow()

                assertEquals(
                    initialUpdatedMs,
                    fixture.getConversationFile(convoId)!!.fileMetadata.updated.milliseconds,
                )
                assertEquals(initialOutboxRows, fixture.outboxRowCount())
            }
        } finally { serviceScope.cancel() }
    }
}
