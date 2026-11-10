package id.homebase.api.client.drives.files

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferStatusTest {

    @Test
    fun serverStatusesDecode_andAnUnknownOneIsNone() {
        fun decode(status: String) = OdinSystemSerializer.json.decodeFromString<RecipientTransferHistoryEntry>(
            """{"recipient":"frodo.dotyou.cloud","lastUpdated":0,"latestTransferStatus":"$status","isInOutbox":true}"""
        ).latestTransferStatus

        assertEquals(TransferStatus.RecipientIdentityOutOfStorage, decode("recipientIdentityOutOfStorage"))
        assertEquals(TransferStatus.RecipientIdentityReturnedVersionTagMismatch, decode("recipientIdentityReturnedVersionTagMismatch"))
        assertEquals(TransferStatus.RecipientIdentityReturnedInvalidKeyHeader, decode("recipientIdentityReturnedInvalidKeyHeader"))
        assertEquals(TransferStatus.None, decode("someStatusFromALaterServer"))
    }

    @Test
    fun aRecipientRefusingTheVersionOrKeyHeader_hasFailed() {
        assertTrue(TransferStatus.RecipientIdentityReturnedVersionTagMismatch.isFailed)
        assertTrue(TransferStatus.RecipientIdentityReturnedInvalidKeyHeader.isFailed)
    }
}
