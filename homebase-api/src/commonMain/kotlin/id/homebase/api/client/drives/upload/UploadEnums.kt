package id.homebase.homebasekmppoc.prototype.lib.drives.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What contents should be sent during transit.
 * Uses bitwise flags.
 */
@Serializable
enum class SendContents(val value: Int) {
    @SerialName("headerOnly")
    HeaderOnly(0),
    
    @SerialName("thumbnails")
    Thumbnails(1),
    
    @SerialName("payload")
    Payload(2),
    
    @SerialName("all")
    All(3); // Thumbnails | Payload

    companion object {
        fun fromInt(value: Int): id.homebase.homebasekmppoc.prototype.lib.drives.upload.SendContents {
            return entries.firstOrNull { it.value == value } ?: HeaderOnly
        }
    }
}

/**
 * Schedule options for transit.
 */
@Serializable
enum class ScheduleOptions(val value: String) {
    @SerialName("sendNowAwaitResponse")
    SendNowAwaitResponse("sendNowAwaitResponse"),
    
    @SerialName("sendAsync")
    SendLater("sendAsync");

    companion object {
        fun fromString(value: String): id.homebase.homebasekmppoc.prototype.lib.drives.upload.ScheduleOptions {
            return entries.firstOrNull { it.value == value } ?: SendLater
        }
    }
}

/**
 * Priority options for transit.
 */
@Serializable
enum class PriorityOptions(val value: Int) {
    High(1),
    Medium(2),
    Low(3);

    companion object {
        fun fromInt(value: Int): id.homebase.homebasekmppoc.prototype.lib.drives.upload.PriorityOptions {
            return entries.firstOrNull { it.value == value } ?: Medium
        }
    }
}

/**
 * Transfer upload status for tracking recipient delivery.
 */
@Serializable
enum class TransferUploadStatus(val value: String) {
    @SerialName("enqueued")
    Enqueued("enqueued"),
    
    @SerialName("enqueuedfailed")
    EnqueuedFailed("enqueuedfailed"),
    
    @SerialName("deliveredtoinbox")
    DeliveredToInbox("deliveredtoinbox"),
    
    @SerialName("deliveredtotargetdrive")
    DeliveredToTargetDrive("deliveredtotargetdrive"),
    
    @SerialName("pendingretry")
    PendingRetry("pendingretry"),
    
    @SerialName("totalrejectionclientshouldretry")
    TotalRejectionClientShouldRetry("totalrejectionclientshouldretry"),
    
    @SerialName("filedoesnotallowdistribution")
    FileDoesNotAllowDistribution("filedoesnotallowdistribution"),
    
    @SerialName("recipientreturnedaccessdenied")
    RecipientReturnedAccessDenied("recipientreturnedaccessdenied"),
    
    @SerialName("recipientdoesnothavepermissiontofileacl")
    RecipientDoesNotHavePermissionToFileAcl("recipientdoesnothavepermissiontofileacl");

    companion object {
        fun fromString(value: String): id.homebase.homebasekmppoc.prototype.lib.drives.upload.TransferUploadStatus {
            return entries.firstOrNull { 
                it.value.equals(value, ignoreCase = true) 
            } ?: Enqueued
        }
    }
}
