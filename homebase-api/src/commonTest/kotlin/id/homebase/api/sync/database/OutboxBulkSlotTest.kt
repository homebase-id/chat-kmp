package id.homebase.api.sync.database

import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Bulk rows may fill two of the three upload slots and never the third; everything else is as it always was. */
class OutboxBulkSlotTest {

    private fun run(body: suspend TestScope.(DatabaseManager, OutboxSync, TestUploader) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager({ createInMemoryDatabase() }, dispatcher = dispatcher, readDispatcher = dispatcher)
        try {
            val uploader = TestUploader()
            val sync = OutboxSync(db, uploader, EventBus(), backgroundScope)
            sync.setOnline(true)
            body(db, sync, uploader)
            sync.clearCheckout(timeoutMs = 5_000)
        } finally {
            db.close()
        }
    }

    // advanceUntilIdle stops at backgroundScope, which is where the workers run.
    private fun TestScope.drain() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private suspend fun DatabaseManager.enqueue(priority: Long): Uuid {
        val id = Uuid.random()
        outbox.insert(
            driveId = Uuid.random(),
            uniqueId = id,
            dependencyUniqueId = null,
            priority = priority,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )
        return id
    }

    @Test
    fun bulkRowsNeverHoldTheThirdSlot() = run { db, sync, uploader ->
        repeat(6) { db.enqueue(OUTBOX_BULK_PRIORITY) }

        sync.send()
        drain()

        assertEquals(6, uploader.uploaded.size)
        assertEquals(2, uploader.maxActive)
    }

    @Test
    fun heldBulkRowsStayQueuedAndChatStillSends() = run { db, sync, uploader ->
        sync.holdBulkSends = { true }
        val bulk = db.enqueue(OUTBOX_BULK_PRIORITY)
        val message = db.enqueue(priority = 1)

        sync.send()
        drain()

        assertEquals(listOf(message), uploader.uploaded.map { it.uniqueId })
        assertEquals(1L, db.outbox.count())
        assertEquals(0L, db.outbox.selectByUniqueId(bulk)?.checkOutCount)

        sync.holdBulkSends = { false }
        sync.send()
        drain()

        assertEquals(setOf(message, bulk), uploader.uploaded.map { it.uniqueId }.toSet())
    }

    @Test
    fun chatStartsAtOnceWhileBulkHoldsItsSlots() = run { db, sync, uploader ->
        repeat(6) { db.enqueue(OUTBOX_BULK_PRIORITY) }
        sync.send()
        runCurrent()
        assertEquals(2, uploader.maxActive)

        val message = db.enqueue(priority = 1)
        sync.send()
        advanceTimeBy(1001)

        assertEquals(3, uploader.maxActive)
        assertTrue(uploader.uploaded.any { it.uniqueId == message }, "the message finished within one upload's time")
        drain()
        assertEquals(7, uploader.uploaded.size)
    }

    @Test
    fun chatStillGetsAllThreeSlots() = run { db, sync, uploader ->
        repeat(5) { db.enqueue(priority = 1) }

        sync.send()
        drain()

        assertEquals(5, uploader.uploaded.size)
        assertEquals(3, uploader.maxActive)
    }

    @Test
    fun chatGoesBeforeQueuedBulk() = run { db, sync, uploader ->
        repeat(3) { db.enqueue(OUTBOX_BULK_PRIORITY) }
        val message = db.enqueue(priority = 100)

        sync.send()
        drain()

        assertEquals(message, uploader.uploaded.first().uniqueId)
        assertEquals(4, uploader.uploaded.size)
    }

    @Test
    fun aBulkFailureFreesItsSlot() = run { db, sync, uploader ->
        uploader.failureException = IllegalStateException("server said no")
        repeat(2) { db.enqueue(OUTBOX_BULK_PRIORITY) }
        sync.send()
        runCurrent()
        uploader.failureException = null

        val message = db.enqueue(priority = 1)
        sync.send()
        advanceTimeBy(1001)

        assertTrue(uploader.uploaded.any { it.uniqueId == message })
    }
}
