package id.homebase.homebasekmppoc.prototype.lib.drives.upload

import id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive
import kotlinx.serialization.Serializable

/** Base transit options for file transfers. */
@Serializable
data class TransitOptions(
    val recipients: List<String>? = null,

    /** If true, file is removed after it's received by all recipients. */
        val isTransient: Boolean? = null,
    val schedule: id.homebase.homebasekmppoc.prototype.lib.drives.upload.ScheduleOptions? = null,
    val priority: id.homebase.homebasekmppoc.prototype.lib.drives.upload.PriorityOptions? = null,
    val sendContents: id.homebase.homebasekmppoc.prototype.lib.drives.upload.SendContents? = null,
    val remoteTargetDrive: id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive? = null,

    /** If true, send app notifications. */
        val useAppNotification: Boolean? = null,

    /** App notification options, required when useAppNotification is true. */
        val appNotificationOptions: id.homebase.homebasekmppoc.prototype.lib.drives.upload.PushNotificationOptions? = null
) {
    companion object {
        /** Create transit options without notifications. */
        fun withoutNotifications(
            recipients: List<String>,
            isTransient: Boolean = false,
            schedule: id.homebase.homebasekmppoc.prototype.lib.drives.upload.ScheduleOptions,
            priority: id.homebase.homebasekmppoc.prototype.lib.drives.upload.PriorityOptions,
            sendContents: id.homebase.homebasekmppoc.prototype.lib.drives.upload.SendContents,
            remoteTargetDrive: id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive? = null
        ): TransitOptions {
            return TransitOptions(
                    recipients = recipients,
                    isTransient = isTransient,
                    schedule = schedule,
                    priority = priority,
                    sendContents = sendContents,
                    remoteTargetDrive = remoteTargetDrive,
                    useAppNotification = false
            )
        }

        /** Create transit options with notifications. */
        fun withNotifications(
            recipients: List<String>,
            isTransient: Boolean = false,
            schedule: id.homebase.homebasekmppoc.prototype.lib.drives.upload.ScheduleOptions,
            priority: id.homebase.homebasekmppoc.prototype.lib.drives.upload.PriorityOptions,
            sendContents: id.homebase.homebasekmppoc.prototype.lib.drives.upload.SendContents,
            remoteTargetDrive: id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive? = null,
            appNotificationOptions: id.homebase.homebasekmppoc.prototype.lib.drives.upload.PushNotificationOptions
        ): TransitOptions {
            return TransitOptions(
                    recipients = recipients,
                    isTransient = isTransient,
                    schedule = schedule,
                    priority = priority,
                    sendContents = sendContents,
                    remoteTargetDrive = remoteTargetDrive,
                    useAppNotification = true,
                    appNotificationOptions = appNotificationOptions
            )
        }

        /** Create transit options with only notifications (no file transfer). */
        fun onlyNotifications(appNotificationOptions: id.homebase.homebasekmppoc.prototype.lib.drives.upload.PushNotificationOptions): TransitOptions {
            return TransitOptions(
                    useAppNotification = true,
                    appNotificationOptions = appNotificationOptions
            )
        }
    }
}
