package id.homebase.chat.services

import id.homebase.api.client.drives.files.RecipientTransferHistoryEntry
import id.homebase.api.client.drives.files.TransferStatus
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
        for (status in listOf(
            TransferStatus.RecipientIdentityReturnedServerError,
            TransferStatus.RecipientIdentityReturnedVersionTagMismatch,
            TransferStatus.RecipientIdentityReturnedInvalidKeyHeader,
        )) {
            val entry = entry(status, isInOutbox = false)
            assertEquals(ChatDeliveryStatus.Failed, entry.toChatDeliveryStatus(), "$status")
            assertEquals(MR.string.error_delivery_failed, entry.latestTransferStatus.toErrorDetailRes(), "$status")
        }
    }

    private fun entry(status: TransferStatus, isInOutbox: Boolean) = RecipientTransferHistoryEntry(
        recipient = "frodo.dotyou.cloud",
        lastUpdated = 0,
        latestTransferStatus = status,
        isInOutbox = isInOutbox,
    )
}
