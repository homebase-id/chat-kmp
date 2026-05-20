package id.homebase.chat.services.convo

import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.chat.services.ChatProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Regression coverage for the group-image-destroyed-on-heal bug.
 *
 * `GroupHealService.healGroupDistribution` resends the main conversation file to
 * Missing peers via `updateConversationInternal(preserveExistingPayloads = true)`.
 * That path re-attaches the existing `convo_img` payload as a pre-encrypted
 * `AppendOrOverwrite`. The bug: it re-attached the payload BYTES but NOT the
 * payload's thumbnails, so the overwrite wiped every server-side thumbnail —
 * the embedded tiny preview in the header survived, but the avatar loader 404'd
 * on each requested size (warning triangle over the tiny thumb), on the sender's
 * own drive and every recipient the author pushed to.
 *
 * Diagnosed from a live desktop repro (homebase.log): `convo_img` shipped with
 * `request.thumbsCount=0`, followed by repeated
 * `HomebaseImageLoader … thumb load failed … key=convo_img … NotFoundException`.
 */
class ConversationServiceResendImageTest {

    @Test
    fun preserveExistingPayloads_reattachesPayloadThumbnails_soOverwriteDoesNotWipeThem() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val byteSource = FakeResendPayloadByteSource()
            val fileOps = FakeFileOperationsProvider()
            val service = fixture.build(
                scope = this,
                driveFileProvider = byteSource,
                fileOperationsProvider = fileOps,
            )

            val peer = "frodo.baggins.demo.rocks"
            val convoId = fixture.seedGroupWithImagePayload(
                others = listOf(peer),
                thumbnailSizes = listOf(72, 320),
                title = "Sammys Ground",
            )

            // Exactly what heal does for the main file: resend to the Missing peer,
            // preserving the existing image payload.
            service.updateConversationInternal(
                conversationId = convoId,
                title = "Sammys Ground",
                participants = listOf(OdinId(fixture.testDomain), OdinId(peer)),
                distribute = true,
                distributeOnlyTo = listOf(OdinId(peer)),
                preserveExistingPayloads = true,
            )

            val updateReq = fixture.drainOutbox()
                .mapNotNull { it.asUpdateFileRequestOrNull() }
                .firstOrNull { it.uniqueId == convoId }
            assertNotNull(updateReq, "an UpdateFileByUniqueIdRequest for the conversation must be enqueued")

            // The payload itself ships — this part already worked before the fix.
            val convoImgPayloads = updateReq.payloads.orEmpty()
                .filter { it.key == ChatProtocol.ConversationImageKey }
            assertEquals(1, convoImgPayloads.size, "convo_img payload must be re-attached")
            assertTrue(
                byteSource.payloadRequests.contains(ChatProtocol.ConversationImageKey),
                "resend must read the existing convo_img payload bytes",
            )

            // REGRESSION ASSERTION: the payload's thumbnails must ship too, or the
            // AppendOrOverwrite wipes them server-side. Empty here == the bug.
            val convoImgThumbs = updateReq.thumbnails.orEmpty()
                .filter { it.key == ChatProtocol.ConversationImageKey }
            assertEquals(
                setOf(72 to 72, 320 to 320),
                convoImgThumbs.map { it.pixelWidth to it.pixelHeight }.toSet(),
                "resend must re-attach ALL of the convo_img payload's thumbnails; got $convoImgThumbs",
            )
            assertTrue(
                convoImgThumbs.all { it.thumbnailBytes.isNotEmpty() },
                "re-attached thumbnails must carry their (pre-encrypted) bytes",
            )
            assertEquals(
                setOf(
                    Triple(ChatProtocol.ConversationImageKey, 72, 72),
                    Triple(ChatProtocol.ConversationImageKey, 320, 320),
                ),
                byteSource.thumbRequests.toSet(),
                "resend must fetch each thumbnail's encrypted bytes from the drive",
            )
        }
    }

    private fun Outbox.asUpdateFileRequestOrNull(): UpdateFileByUniqueIdRequest? {
        if (this.uploadType != DriveOutboxUploader.UpdateFile) return null
        return try {
            OdinSystemSerializer.deserialize<UpdateFileByUniqueIdRequest>(this.json.decodeToString())
        } catch (_: Throwable) {
            null
        }
    }
}
