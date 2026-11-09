package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SurfacedListenerTest {
    private val me = OdinId("frodo.test")

    private val message = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.NIL,
        content = "hi",
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = me,
        sender = me,
        displayName = "",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader.empty(),
        isDeleted = false,
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )

    @Test fun a_throwing_listener_does_not_skip_the_next_one() = runTest {
        val ran = mutableListOf<String>()
        listOf(
            SurfacedListener { ran += "a"; error("boom") },
            SurfacedListener { ran += "b" },
        ).runAll(listOf(message))
        assertEquals(listOf("a", "b"), ran)
    }

    @Test fun cancellation_propagates_and_stops_later_listeners() = runTest {
        val ran = mutableListOf<String>()
        assertFailsWith<CancellationException> {
            listOf(
                SurfacedListener { throw CancellationException("cancelled") },
                SurfacedListener { ran += "b" },
            ).runAll(listOf(message))
        }
        assertEquals(emptyList(), ran)
    }

    @Test fun empty_batches_run_no_listeners() = runTest {
        var ran = false
        listOf(SurfacedListener { ran = true }).runAll(emptyList())
        assertEquals(false, ran)
    }
}
