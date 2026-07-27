package id.homebase.api.client.notifications

import id.homebase.api.common.time.UnixTimeUtc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Pure-logic tests for the client-side `list()` reconcile that stands in for the missing server
 * idempotency/upsert (see [ScheduledPushOutboxUploader]). Everything keys off `tagId`.
 */
class ScheduledPushScheduleDecisionTest {

    private val appId = Uuid.random()
    private val convo = Uuid.random()

    private fun entry(tagId: Uuid, sendAtMs: Long, jobId: Uuid = Uuid.random()) =
        ScheduledPushNotificationEntry(
            jobId = jobId,
            options = ScheduledPushNotificationOptions(
                appId = appId, typeId = convo, tagId = tagId, silent = false,
            ),
            sendAt = UnixTimeUtc(sendAtMs),
            state = ScheduledPushNotificationState.Scheduled,
            attemptCount = 0,
            maxAttempts = 3,
        )

    @Test
    fun noExistingJob_forTag_createsNew() {
        val tag = Uuid.random()
        val existing = listOf(entry(Uuid.random(), 1_000L)) // different tag
        assertEquals(ScheduleAction.Create, decideScheduleAction(existing, tag, 5_000L))
    }

    @Test
    fun sameTagSameSendAt_skips() {
        val tag = Uuid.random()
        val existing = listOf(entry(tag, 5_000L))
        assertEquals(ScheduleAction.Skip, decideScheduleAction(existing, tag, 5_000L))
    }

    @Test
    fun sameTagDifferentSendAt_updatesThatJob() {
        val tag = Uuid.random()
        val job = Uuid.random()
        val existing = listOf(entry(tag, 5_000L, jobId = job))
        assertEquals(ScheduleAction.Update(job), decideScheduleAction(existing, tag, 9_000L))
    }

    @Test
    fun cancelForTag_returnsEveryJobForThatTagOnly() {
        val tag = Uuid.random()
        val other = Uuid.random()
        val a = Uuid.random()
        val b = Uuid.random()
        val existing = listOf(
            entry(tag, 5_000L, jobId = a),
            entry(other, 5_000L, jobId = Uuid.random()),
            entry(tag, 9_000L, jobId = b),   // an accumulated duplicate — must also be cancelled
        )
        assertEquals(setOf(a, b), jobsToCancelForTag(existing, tag).toSet())
    }

    @Test
    fun cancelForTag_noMatch_isEmpty() {
        assertEquals(emptyList(), jobsToCancelForTag(listOf(entry(Uuid.random(), 5_000L)), Uuid.random()))
    }
}
