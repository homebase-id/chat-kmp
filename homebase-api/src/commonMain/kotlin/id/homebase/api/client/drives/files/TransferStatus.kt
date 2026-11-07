package id.homebase.api.client.drives.files

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Transfer status for file transfers between identities. Ported from TypeScript TransferStatus
 * enum.
 */
@Serializable
enum class TransferStatus {
    @SerialName("none") None,
    @SerialName("delivered") Delivered,
    @SerialName("recipientidentityreturnedaccessdenied")
    RecipientIdentityReturnedAccessDenied,
    @SerialName("sourcefiledoesnotallowdistribution")
    SourceFileDoesNotAllowDistribution,
    @SerialName("recipientservernotresponding")
    RecipientServerNotResponding,
    @SerialName("recipientidentityreturnedservererror")
    RecipientIdentityReturnedServerError,
    /** The recipient is out of storage: still in the outbox, delivered once they have room. Waiting, not failed. */
    @SerialName("recipientidentityoutofstorage")
    RecipientIdentityOutOfStorage,
    @SerialName("recipientidentityreturnedbadrequest")
    RecipientIdentityReturnedBadRequest,
    @SerialName("recipientidentityreturnedversiontagmismatch")
    RecipientIdentityReturnedVersionTagMismatch,
    @SerialName("recipientidentityreturnedinvalidkeyheader")
    RecipientIdentityReturnedInvalidKeyHeader,
    @SerialName("unknownservererror") UnknownServerError,
    @SerialName("sendingservertoomanyattempts")
    SendingServerTooManyAttempts;

    /** Every entry is listed, so a new one won't compile until it's decided. */
    val isFailed: Boolean
        get() = when (this) {
            RecipientIdentityReturnedAccessDenied,
            SourceFileDoesNotAllowDistribution,
            RecipientServerNotResponding,
            RecipientIdentityReturnedServerError,
            RecipientIdentityReturnedBadRequest,
            RecipientIdentityReturnedVersionTagMismatch,
            RecipientIdentityReturnedInvalidKeyHeader,
            UnknownServerError,
            SendingServerTooManyAttempts -> true
            None, Delivered, RecipientIdentityOutOfStorage -> false
        }

    companion object {
        fun isFailedStatus(status: TransferStatus): Boolean = status.isFailed
    }
}
