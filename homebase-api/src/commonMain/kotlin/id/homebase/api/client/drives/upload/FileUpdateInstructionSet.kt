package id.homebase.api.client.drives.upload

import id.homebase.api.prototype.lib.serialization.Base64ByteArraySerializer
import kotlinx.serialization.Serializable

@Serializable
data class FileUpdateInstructionSet(
    @Serializable(with = Base64ByteArraySerializer::class)
    val transferIv: ByteArray,

    val locale: UpdateLocale,

    val recipients: List<String>,

    val manifest: UpdateManifest,

    val useAppNotification: Boolean = false,

    val appNotificationOptions: AppNotificationOptions? = null
)