package id.homebase.chat.services

import id.homebase.api.client.drives.files.RecipientTransferHistoryEntry
import id.homebase.api.client.drives.files.TransferStatus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.resources.MR
import id.homebase.resources.error_delivery_failed
import id.homebase.resources.error_recipient_storage_full
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per-recipient status in Message Info: an entry still in the sender's outbox is still trying, whatever the last
 * attempt said, and a recipient out of storage is shown as waiting for room rather than failed.
 */
class RecipientDeliveryStatusTest {

    @Test
    fun outOfStorageInOutbox_isSendingAndSaysWhy() {
        val entry = entry(TransferStatus.RecipientIdentityOutOfStorage, isInOutbox = true)
        assertEquals(ChatDeliveryStatus.Sending, entry.toChatDeliveryStatus())
        assertEquals(MR.string.error_recipient_storage_full, entry.latestTransferStatus.toErrorDetailRes())
    }

    @Test
    fun retryableErrorStillInOutbox_isSending() {
        assertEquals(ChatDeliveryStatus.Sending,
            entry(TransferStatus.RecipientIdentityReturnedServerError, isInOutbox = true).toChatDeliveryStatus())
    }

    @Test
    fun errorOutOfTheOutbox_isFailed() {
        val entry = entry(TransferStatus.RecipientIdentityReturnedServerError, isInOutbox = false)
        assertEquals(ChatDeliveryStatus.Failed, entry.toChatDeliveryStatus())
        assertEquals(MR.string.error_delivery_failed, entry.latestTransferStatus.toErrorDetailRes())
    }

    @Test
    fun serverStatusesDecode_andAnUnknownOneIsNone() {
        fun decode(status: String) = OdinSystemSerializer.json.decodeFromString<RecipientTransferHistoryEntry>(
            """{"recipient":"frodo.dotyou.cloud","lastUpdated":0,"latestTransferStatus":"$status","isInOutbox":true}"""
        ).latestTransferStatus

        assertEquals(TransferStatus.RecipientIdentityOutOfStorage, decode("recipientIdentityOutOfStorage"))
        assertEquals(TransferStatus.None, decode("someStatusFromALaterServer"))
    }

    private fun entry(status: TransferStatus, isInOutbox: Boolean) = RecipientTransferHistoryEntry(
        recipient = "frodo.dotyou.cloud",
        lastUpdated = 0,
        latestTransferStatus = status,
        isInOutbox = isInOutbox,
    )
}
