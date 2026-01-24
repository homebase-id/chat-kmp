package id.homebase.chat.config

import id.homebase.api.youauth.AppPermissionType
import id.homebase.api.youauth.DrivePermissionType
import id.homebase.homebasekmppoc.lib.youauth.PermissionExtensionConfig
import id.homebase.api.youauth.TargetDriveAccessRequest
import id.homebase.api.client.drives.TargetDrive
import kotlin.uuid.Uuid

/**
 * Central app configuration for authentication, permissions, and drives. Used by both
 * LoginViewModel (for initial auth) and HomeViewModel (for permission checking).
 */
object AppConfig {
    const val APP_ID = "0cecc6fe033e48b19ee6a4f60318be02"
    const val APP_NAME = "Homebase - KMP POC"

    // Deep link scheme for returning from permission extension
    const val DEEP_LINK_SCHEME = "homebase-kmp"
    const val RETURN_URL = "$DEEP_LINK_SCHEME://permission-callback"
}

// Circle IDs for connected identities
const val CONFIRMED_CONNECTIONS_CIRCLE_ID = "bb2683fa402aff866e771a6495765a15"
const val AUTO_CONNECTIONS_CIRCLE_ID = "9e22b42952f74d2580e11250b651d343"

// Target drives
val feedTargetDrive =
        TargetDrive(
                alias = Uuid.parse("4db49422ebad02e99ab96e9c477d1e08"),
                type = Uuid.parse("a3227ffba87608beeb24fee9b70d92a6")
        )

val chatTargetDrive =
        TargetDrive(
                alias = Uuid.parse("9ff813aff2d61e2f9b9db189e72d1a11"),
                type = Uuid.parse("66ea8355ae4155c39b5a719166b510e3")
        )

val contactTargetDrive =
        TargetDrive(
                alias = Uuid.parse("2612429d1c3f037282b8d42fb2cc0499"),
                type = Uuid.parse("70e92f0f94d05f5c7dcd36466094f3a5")
        )

// App permissions required
val appPermissions: List<AppPermissionType> =
        listOf(
                AppPermissionType.ReadConnections,
                AppPermissionType.ReadConnectionRequests,
                AppPermissionType.ReadCircleMembers,
                AppPermissionType.ReadWhoIFollow,
                AppPermissionType.ReadMyFollowers,
                AppPermissionType.SendDataToOtherIdentitiesOnMyBehalf,
                AppPermissionType.ReceiveDataFromOtherIdentitiesOnMyBehalf,
                AppPermissionType.SendPushNotifications,
                AppPermissionType.SendIntroductions,
        )

// Target drive access requests
val targetDriveAccessRequest: List<TargetDriveAccessRequest> =
        listOf(
                TargetDriveAccessRequest(
                        alias = feedTargetDrive.alias.toString(),
                        type = feedTargetDrive.type.toString(),
                        name = "Feed Drive",
                        description = " ",
                        permissions = listOf(DrivePermissionType.Read, DrivePermissionType.Write)
                ),
                TargetDriveAccessRequest(
                        alias = chatTargetDrive.alias.toString(),
                        type = chatTargetDrive.type.toString(),
                        name = "Chat Drive",
                        description = "Drive which contains all the chat messages",
                        permissions =
                                listOf(
                                        DrivePermissionType.Read,
                                        DrivePermissionType.Write,
                                        DrivePermissionType.React
                                )
                ),
                TargetDriveAccessRequest(
                        alias = contactTargetDrive.alias.toString(),
                        type = contactTargetDrive.type.toString(),
                        name = " ",
                        description = " ",
                        permissions = listOf(DrivePermissionType.Read, DrivePermissionType.Write)
                )
        )

// Drives we listen to for sockets and synchronization
val syncDrives: List<TargetDrive> =
    targetDriveAccessRequest.map { request ->
        TargetDrive(
            alias = Uuid.parse(request.alias),
            type = Uuid.parse(request.type)
        )
    }


// Circle drive requests
val circleDriveTargetRequest: List<TargetDriveAccessRequest> =
        listOf(
                TargetDriveAccessRequest(
                        alias = chatTargetDrive.alias.toString(),
                        type = chatTargetDrive.type.toString(),
                        name = "Chat Drive",
                        description = "Drive which contains all the chat messages",
                        permissions = listOf(DrivePermissionType.Write, DrivePermissionType.React)
                )
        )


/**
 * Get the permission extension config for checking missing permissions. Uses the same drives and
 * permissions as the login flow.
 */
fun getPermissionExtensionConfig(): PermissionExtensionConfig {
    return PermissionExtensionConfig(
            appId = AppConfig.APP_ID,
            appName = AppConfig.APP_NAME,
            drives = targetDriveAccessRequest,
            circleDrives = circleDriveTargetRequest,
            permissions = appPermissions,
            needsAllConnected = true,
            returnUrl = AppConfig.RETURN_URL
    )
}
