package id.homebase.chat.groupsettings

import id.homebase.api.client.drives.files.RecipientTransferHistoryEntry
import id.homebase.api.client.drives.files.TransferStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecipientFileStatusTest {

    @Test
    fun justCreatedGroupStillInOutbox_isPendingNotProblem() {
        assertEquals(RecipientFileStatus.Pending, entry(TransferStatus.None, isInOutbox = true).toRecipientFileStatus())
    }

    @Test
    fun retryableErrorStillInOutbox_isPending() {
        assertEquals(
            RecipientFileStatus.Pending,
            entry(TransferStatus.RecipientServerNotResponding, isInOutbox = true).toRecipientFileStatus(),
        )
        assertEquals(
            RecipientFileStatus.Pending,
            entry(TransferStatus.RecipientIdentityOutOfStorage, isInOutbox = true).toRecipientFileStatus(),
        )
    }

    @Test
    fun delivered_isOk_evenWhenReEnqueuedByHeal() {
        assertEquals(RecipientFileStatus.Ok, entry(TransferStatus.Delivered, isInOutbox = true).toRecipientFileStatus())
        assertEquals(RecipientFileStatus.Ok, entry(TransferStatus.Delivered, isInOutbox = false).toRecipientFileStatus())
    }

    @Test
    fun failedAndOutOfTheOutbox_isProblem() {
        val status = entry(TransferStatus.RecipientIdentityReturnedAccessDenied, isInOutbox = false).toRecipientFileStatus()
        assertIs<RecipientFileStatus.Problem>(status)
        assertEquals(TransferStatus.RecipientIdentityReturnedAccessDenied, status.rawStatus)
    }

    private fun entry(status: TransferStatus, isInOutbox: Boolean) = RecipientTransferHistoryEntry(
        recipient = "frodo.dotyou.cloud",
        lastUpdated = 0,
        latestTransferStatus = status,
        isInOutbox = isInOutbox,
    )
}
