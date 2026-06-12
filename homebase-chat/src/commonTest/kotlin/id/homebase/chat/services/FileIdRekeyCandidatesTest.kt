package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [fileIdRekeyCandidates] — detecting the optimistic→server fileId transition
 * at sync-back so the seeded payload-cache entries can be re-keyed before the
 * old fileId is lost with the window update.
 */
class FileIdRekeyCandidatesTest {

    private val conversationId = Uuid.random()

    private fun model(
        id: Uuid = Uuid.random(),
        fileId: Uuid = Uuid.random(),
        isPendingSend: Boolean = false,
        payloads: List<PayloadDescriptor>? = listOf(PayloadDescriptor(key = "img_key1")),
    ) = MessageUiModel(
        id = id,
        globalTransitId = null,
        fileId = fileId,
        conversationId = conversationId,
        content = "",
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = OdinId("owner.test"),
        sender = OdinId("owner.test"),
        displayName = "owner.test",
        localReadTimestamp = null,
        isDeleted = false,
        isPendingSend = isPendingSend,
        versionTag = Uuid.NIL,
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = payloads?.toImmutableList(),
        keyHeader = KeyHeader.empty(),
        hasMore = false,
    )

    @Test
    fun pendingSendWithChangedFileIdAndPayloadsIsACandidate() {
        val id = Uuid.random()
        val old = model(id = id, isPendingSend = true)
        val incoming = model(id = id)

        val candidates = fileIdRekeyCandidates(listOf(old), listOf(incoming))

        assertEquals(listOf(old.fileId to incoming), candidates)
    }

    @Test
    fun unchangedFileIdIsNotACandidate() {
        val id = Uuid.random()
        val fileId = Uuid.random()
        val old = model(id = id, fileId = fileId, isPendingSend = true)
        val incoming = model(id = id, fileId = fileId)

        assertTrue(fileIdRekeyCandidates(listOf(old), listOf(incoming)).isEmpty())
    }

    @Test
    fun settledMessageIsNotACandidate() {
        // fileId changes without a pending tag (e.g. server-side restore) —
        // nothing was seeded under the old id, so nothing to move.
        val id = Uuid.random()
        val old = model(id = id, isPendingSend = false)
        val incoming = model(id = id)

        assertTrue(fileIdRekeyCandidates(listOf(old), listOf(incoming)).isEmpty())
    }

    @Test
    fun incomingWithoutAnInMemoryModelIsNotACandidate() {
        val old = model(isPendingSend = true)
        val incoming = model() // different id — window never saw it

        assertTrue(fileIdRekeyCandidates(listOf(old), listOf(incoming)).isEmpty())
    }

    @Test
    fun incomingWithoutPayloadsIsNotACandidate() {
        val id = Uuid.random()
        val old = model(id = id, isPendingSend = true)

        for (payloads in listOf(null, emptyList<PayloadDescriptor>())) {
            val incoming = model(id = id, payloads = payloads)
            assertTrue(
                fileIdRekeyCandidates(listOf(old), listOf(incoming)).isEmpty(),
                "text-only message (payloads=$payloads) has nothing seeded worth moving",
            )
        }
    }

    @Test
    fun mixedBatchYieldsOnlyQualifyingCandidatesInOrder() {
        val idA = Uuid.random()
        val idB = Uuid.random()
        val idC = Uuid.random()
        val oldA = model(id = idA, isPendingSend = true)
        val oldB = model(id = idB, isPendingSend = false) // settled — no
        val oldC = model(id = idC, isPendingSend = true)
        val incomingA = model(id = idA)
        val incomingB = model(id = idB)
        val incomingC = model(id = idC)

        val candidates = fileIdRekeyCandidates(
            listOf(oldA, oldB, oldC),
            listOf(incomingA, incomingB, incomingC),
        )

        assertEquals(listOf(oldA.fileId to incomingA, oldC.fileId to incomingC), candidates)
    }
}
