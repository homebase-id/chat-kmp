package id.homebase.api.client.drives.upload

import id.homebase.api.client.drives.TargetDrive
import kotlinx.serialization.Serializable

/** Base transit options for file transfers. */
@Serializable
data class TransitOptions(
    val recipients: List<String>? = null,

    /** If true, file is removed after it's received by all recipients. */
        val isTransient: Boolean? = null,
    val schedule: ScheduleOptions? = null,
    val priority: PriorityOptions? = null,
    val sendContents: SendContents? = null,
    val remoteTargetDrive: TargetDrive? = null,

    /** If true, send app notifications. */
        val useAppNotification: Boolean? = null,

    /** App notification options, required when useAppNotification is true. */
        val appNotificationOptions: PushNotificationOptions? = null
) {
    companion object {
        /** Create transit options without notifications. */
        fun withoutNotifications(
            recipients: List<String>,
            isTransient: Boolean = false,
            schedule: ScheduleOptions,
            priority: PriorityOptions,
            sendContents: SendContents,
            remoteTargetDrive: TargetDrive? = null
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
            schedule: ScheduleOptions,
            priority: PriorityOptions,
            sendContents: SendContents,
            remoteTargetDrive: TargetDrive? = null,
            appNotificationOptions: PushNotificationOptions
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
        fun onlyNotifications(appNotificationOptions: PushNotificationOptions): TransitOptions {
            return TransitOptions(
                    useAppNotification = true,
                    appNotificationOptions = appNotificationOptions
            )
        }
    }
}
