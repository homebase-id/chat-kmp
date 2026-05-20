package id.homebase.chat.data

import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Pure unit tests for the lastRead/dirty rules that live on the entity:
 * [ConversationUiModel.resolveLastReadAdvance], [ConversationUiModel.advancedLastRead],
 * and [ConversationUiModel.clearedDirtyIfUnchanged]. No DB, no service — the
 * decision logic is the model's own, so it's tested as the model's own.
 */
class ConversationUiModelLastReadTest {

    private fun model(
        lastReadMs: Long,
        latestMs: Long,
        dirty: Boolean = false,
    ) = ConversationUiModel(
        id = Uuid.random(),
        name = "",
        lastMessage = "",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(latestMs),
        avatarInitials = "",
        avatarTiny = null,
        lastRead = Instant.fromEpochMilliseconds(lastReadMs),
        dirty = dirty,
        avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
        admins = emptySet(),
    )

    private fun instant(ms: Long) = Instant.fromEpochMilliseconds(ms)

    // ---- resolveLastReadAdvance (the gate) ----

    @Test
    fun resolveLastReadAdvance_returnsCandidate_whenStrictlyNewerAndNotSaturated() {
        val convo = model(lastReadMs = 1_000, latestMs = 10_000)
        assertEquals(instant(5_000), convo.resolveLastReadAdvance(instant(5_000)))
    }

    @Test
    fun resolveLastReadAdvance_null_whenNotAdvancing() {
        val convo = model(lastReadMs = 5_000, latestMs = 10_000)
        assertNull(convo.resolveLastReadAdvance(instant(5_000)), "equal is not an advance")
        assertNull(convo.resolveLastReadAdvance(instant(4_999)), "older is not an advance")
    }

    @Test
    fun resolveLastReadAdvance_null_whenSaturated() {
        // Already read every known message — advancing further is meaningless.
        val convo = model(lastReadMs = 5_000, latestMs = 5_000)
        assertNull(convo.resolveLastReadAdvance(instant(7_000)))
    }

    // ---- advancedLocally (gate → move lastRead + set dirty, together) ----

    @Test
    fun advancedLastRead_movesLastReadAndSetsDirty_onGenuineAdvance() {
        val convo = model(lastReadMs = 1_000, latestMs = 10_000, dirty = false)
        val advanced = convo.advancedLastRead(instant(5_000))
        assertEquals(instant(5_000), advanced.lastRead, "lastRead moves up")
        assertTrue(advanced.dirty, "dirty is set in the same transition")
    }

    @Test
    fun advancedLastRead_returnsSameInstance_whenNotAnAdvance() {
        val convo = model(lastReadMs = 5_000, latestMs = 10_000)
        assertSame(convo, convo.advancedLastRead(instant(4_999)), "non-advance is a no-op")
    }

    @Test
    fun advancedLastRead_returnsSameInstance_whenSaturated() {
        val convo = model(lastReadMs = 5_000, latestMs = 5_000)
        assertSame(convo, convo.advancedLastRead(instant(7_000)), "saturated is a no-op")
    }

    // ---- clearedDirtyIfUnchanged (compare-and-clear) ----

    @Test
    fun clearedDirtyIfUnchanged_clears_whenLastReadStillMatchesPushedValue() {
        val convo = model(lastReadMs = 5_000, latestMs = 10_000, dirty = true)
        val cleared = convo.clearedDirtyIfUnchanged(5_000)
        assertFalse(cleared.dirty, "matching pushed value clears dirty")
    }

    @Test
    fun clearedDirtyIfUnchanged_leavesDirty_whenLastReadAdvancedSincePush() {
        // A newer advance landed (lastRead now 7000) while 5000 was being pushed:
        // stay dirty so 7000 goes out next round.
        val convo = model(lastReadMs = 7_000, latestMs = 10_000, dirty = true)
        val result = convo.clearedDirtyIfUnchanged(5_000)
        assertTrue(result.dirty, "a newer advance must keep the conversation dirty")
        assertSame(convo, result)
    }

    @Test
    fun clearedDirtyIfUnchanged_noOp_whenNotDirty() {
        val convo = model(lastReadMs = 5_000, latestMs = 10_000, dirty = false)
        assertSame(convo, convo.clearedDirtyIfUnchanged(5_000))
    }

    // ---- reconciledWithRemoteLastRead (merge against a received file) ----

    @Test
    fun reconciledWithRemoteLastRead_peerAhead_takesRemoteAndClearsDirty() {
        // A peer device read further than us; their value arrives. lastRead
        // advances to theirs and we no longer owe a push (the server has it).
        val convo = model(lastReadMs = 2_000, latestMs = 10_000, dirty = true)
        val merged = convo.reconciledWithRemoteLastRead(instant(3_000))
        assertEquals(instant(3_000), merged.lastRead, "lastRead takes the higher remote")
        assertFalse(merged.dirty, "remote >= local retires the pending push")
    }

    @Test
    fun reconciledWithRemoteLastRead_equal_clearsDirty() {
        // Our own pushed advance echoes back at the same value: drop the flag.
        val convo = model(lastReadMs = 3_000, latestMs = 10_000, dirty = true)
        val merged = convo.reconciledWithRemoteLastRead(instant(3_000))
        assertEquals(instant(3_000), merged.lastRead, "lastRead unchanged")
        assertFalse(merged.dirty, "remote == local retires the pending push")
    }

    @Test
    fun reconciledWithRemoteLastRead_localAhead_keepsLocalAndStaysDirty() {
        // Our local advance still leads the network — keep it and keep owing.
        val convo = model(lastReadMs = 5_000, latestMs = 10_000, dirty = true)
        val merged = convo.reconciledWithRemoteLastRead(instant(3_000))
        assertEquals(instant(5_000), merged.lastRead, "lastRead keeps the higher local")
        assertTrue(merged.dirty, "a still-leading local advance keeps owing a push")
    }

    @Test
    fun reconciledWithRemoteLastRead_notDirty_staysNotDirty() {
        // Nothing was owed; a remote advance must not invent a push obligation.
        val advancing = model(lastReadMs = 2_000, latestMs = 10_000, dirty = false)
        val a = advancing.reconciledWithRemoteLastRead(instant(3_000))
        assertEquals(instant(3_000), a.lastRead)
        assertFalse(a.dirty, "clean stays clean even as lastRead advances")

        val leading = model(lastReadMs = 5_000, latestMs = 10_000, dirty = false)
        val b = leading.reconciledWithRemoteLastRead(instant(3_000))
        assertEquals(instant(5_000), b.lastRead)
        assertFalse(b.dirty)
    }
}
