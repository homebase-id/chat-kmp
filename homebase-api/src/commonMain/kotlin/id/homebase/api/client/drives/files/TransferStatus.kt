package id.homebase.homebasekmppoc.prototype.lib.drives.files

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Transfer status for file transfers between identities. Ported from TypeScript TransferStatus
 * enum.
 */
@Serializable
enum class TransferStatus(val value: String) {
    @SerialName("none") None("none"),
    @SerialName("delivered") Delivered("delivered"),
    @SerialName("recipientidentityreturnedaccessdenied")
    RecipientIdentityReturnedAccessDenied("recipientidentityreturnedaccessdenied"),
    @SerialName("sourcefiledoesnotallowdistribution")
    SourceFileDoesNotAllowDistribution("sourcefiledoesnotallowdistribution"),
    @SerialName("recipientservernotresponding")
    RecipientServerNotResponding("recipientservernotresponding"),
    @SerialName("recipientidentityreturnedservererror")
    RecipientIdentityReturnedServerError("recipientidentityreturnedservererror"),
    @SerialName("recipientidentityreturnedbadrequest")
    RecipientIdentityReturnedBadRequest("recipientidentityreturnedbadrequest"),
    @SerialName("unknownservererror") UnknownServerError("unknownservererror"),
    @SerialName("sendingservertoomanyattempts")
    SendingServerTooManyAttempts("sendingservertoomanyattempts");

    companion object {
        fun fromString(value: String): id.homebase.homebasekmppoc.prototype.lib.drives.files.TransferStatus {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: None
        }

        /** List of failed transfer statuses. */
        val failedStatuses: List<id.homebase.homebasekmppoc.prototype.lib.drives.files.TransferStatus> =
                listOf(
                        RecipientIdentityReturnedAccessDenied,
                        SourceFileDoesNotAllowDistribution,
                        RecipientServerNotResponding,
                        RecipientIdentityReturnedServerError,
                        RecipientIdentityReturnedBadRequest,
                        UnknownServerError,
                        SendingServerTooManyAttempts
                )

        fun isFailedStatus(status: id.homebase.homebasekmppoc.prototype.lib.drives.files.TransferStatus): Boolean {
            return status in failedStatuses
        }
    }
}
