package id.homebase.api.client.drives.files

import kotlinx.serialization.Serializable

/** Summary of transfer recipients. Ported from TypeScript RecipientTransferSummary interface. */
@Serializable
data class RecipientTransferSummary(
        val totalInOutbox: Int = 0,
        val totalFailed: Int = 0,
        val totalDelivered: Int = 0,
        val totalReadByRecipient: Int = 0
)

/**
 * Individual recipient transfer history entry. Ported from TypeScript RecipientTransferHistory
 * interface.
 */
@Serializable
data class RecipientTransferHistoryEntry(
    val recipient: String,
    val lastUpdated: Long,
    val latestTransferStatus: TransferStatus,
    val isInOutbox: Boolean,
    val latestSuccessfullyDeliveredVersionTag: String? = null,
    val isReadByRecipient: Boolean = false
)

/** Transfer history with pagination. Ported from TypeScript TransferHistory interface. */
@Serializable
data class TransferHistory(val originalRecipientCount: Int, val history: TransferHistoryPage)

/** Paginated transfer history results. */
@Serializable
data class TransferHistoryPage(
    val request: TransferHistoryRequest,
    val totalPages: Int,
    val results: List<RecipientTransferHistoryEntry>
)

/** Request parameters for transfer history pagination. */
@Serializable data class TransferHistoryRequest(val pageNumber: Int, val pageSize: Int)
