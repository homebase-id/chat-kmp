package id.homebase.core.ui.screens.card

import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.PermissionExtensionConfig
import id.homebase.api.youauth.SecurityContext
import id.homebase.api.youauth.TargetDriveAccessRequest
import id.homebase.api.youauth.drivePermissions
import id.homebase.core.config.AppConfig
import id.homebase.core.config.returnUrl
import id.homebase.core.feed.services.FeedProtocol
import kotlin.uuid.Uuid

/** Read with the storage key on each of [channels]; the permission manager drops the ones the app already decrypts. */
fun channelAccessConfig(channels: List<Uuid>, context: SecurityContext): PermissionExtensionConfig {
    val type = FeedProtocol.ChannelDriveType.toHexString()
    return PermissionExtensionConfig(
        appId = AppConfig.APP_ID,
        appName = AppConfig.APP_NAME,
        drives = channels.map { channel ->
            val alias = channel.toHexString()
            TargetDriveAccessRequest(
                alias = alias,
                type = type,
                // A blank name keeps the owner console from creating a channel deleted since this check.
                name = "",
                description = "",
                // The owner console replaces the app's grant on a drive with the requested one, so keep what it has.
                permissions = (context.drivePermissions(alias, type) + DrivePermission.Read).distinct(),
                requireStorageKey = true,
            )
        },
        permissions = emptyList(),
        returnUrl = ::returnUrl,
    )
}
