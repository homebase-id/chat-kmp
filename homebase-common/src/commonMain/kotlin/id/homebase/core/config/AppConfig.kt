package id.homebase.core.config

import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.Md5
import id.homebase.api.youauth.AppPermissionType
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.PermissionExtensionConfig
import id.homebase.api.youauth.TargetDriveAccessRequest
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * A drive the app mounts for sync, paired with a human-readable label.
 *
 * @param ownerOdinId the **owning identity** when this drive is hosted on a peer (a community
 *   owner's collaborative drive); null for the logged-in user's own drives. When set, the drive is
 *   synced/queried/written over peer and gets a per-owner peer websocket instead of riding the
 *   user's own-host websocket. Nullable with a default so existing serialized registry files (which
 *   predate this field) still parse as own drives.
 */
@Serializable
data class LabeledDrive(
    val drive: TargetDrive,
    val label: String,
    val ownerOdinId: OdinId? = null,
)

/**
 * Central app configuration for authentication, permissions, and drives. Used by both
 * LoginViewModel (for initial auth) and HomeViewModel (for permission checking).
 */
object AppConfig {
    const val APP_ID = "2d78140138044b57b4aad8e4e2ef39f4"

    const val APP_NAME = "Homebase - Chat"

    // Deep link scheme for returning from permission extension
    const val DEEP_LINK_SCHEME = "homebase-fchat"

    const val REPORT_CONTENT_URL = "https://ravenhosting.cloud/report/content"
}

/**
 * Return URL the owner console redirects the browser to once the user has finished
 * extending app permissions. Platform-specific because the mechanism differs:
 *
 * - **Mobile (Android/iOS) and Web**: a custom URL scheme deep link
 *   (`homebase-fchat://permission-callback`) registered on the device.
 * - **Desktop (JVM)**: a localhost loopback URL handled by the in-process
 *   [id.homebase.api.browser.LocalCallbackServer] (the same server the OAuth login
 *   flow uses). The implementation must ensure the server is running before returning.
 *
 * Invoked at URL-build time (per extend-permissions click) via the lambda in
 * [PermissionExtensionConfig.returnUrl], not at config-build time — so a JVM
 * callback server that was stopped between checks is restarted, and the URL
 * carries a live port.
 */
expect fun returnUrl(): String

/**
 * Return URL the owner data-upgrade page redirects to once the upgrade completes.
 * Same platform split as [returnUrl]: deep link on mobile, localhost loopback on desktop.
 */
expect fun dataUpgradeReturnUrl(): String

// Circle IDs for connected identities
const val CONFIRMED_CONNECTIONS_CIRCLE_ID = "bb2683fa402aff866e771a6495765a15"
const val AUTO_CONNECTIONS_CIRCLE_ID = "9e22b42952f74d2580e11250b651d343"

/**
 * Well-known GUID (N-format) of the circle whose members may see this identity's location in an
 * emergency. Matching by id rather than name survives a rename; the owner-console "manage" deep link
 * uses the same id. Granting it server-side gives the member `ConditionalTemporalRead` on the
 * location drive.
 */
const val EMERGENCY_LOCATION_CIRCLE_ID = "8b5383a5927246f8a666f4f3fcb7392b"

// TypeIds
const val OWNER_FOLLOWER_TYPE_ID = "2cc468af-109b-4216-8119-542401e32f4d"
const val OWNER_CONNECTION_REQUEST_TYPE_ID = "8ee62e9e-c224-47ad-b663-21851207f768"
const val OWNER_CONNECTION_ACCEPTED_TYPE_ID = "79f0932a-056e-490b-8208-3a820ad7c321"
const val OWNER_INTRODUCTION_RECEIVED_TYPE_ID = "f100bfa0-ac4e-468a-9322-bdaf6059ec8a"
const val OWNER_INTRODUCTION_ACCEPTED_TYPE_ID = "f56ee792-56dd-45fd-8f9e-f96bb5d0e3de"
const val FEED_NEW_CONTENT_TYPE_ID = "ad695388-c2df-47a0-ad5b-fc9f9e1fffc9"
const val FEED_NEW_REACTION_TYPE_ID = "37dae95d-e137-4bd4-b782-8512aaa2c96a"
const val FEED_NEW_COMMENT_TYPE_ID = "1e08b70a-3826-4840-8372-18410bfc02c7"

// APP IDs
const val MAIL_APP_ID = "6e8ecfff-7c15-40e4-94f4-d6e83bfb5857"
const val FEED_APP_ID = "5f887d80-0132-4294-ba40-bda79155551d"
const val PHOTO_APP_ID = "32f0bdbf-017f-4fc0-8004-2d4631182d1e"
const val OWNER_APP_ID = "ac126e09-54cb-4878-a690-856be692da16"
const val COMMUNITY_APP_ID = "77ed6136-6b33-4654-8088-3d89c91e6065"

// Labeled drives — drive definition co-located with its human-readable label
val chatLabeledDrive = LabeledDrive(drive = SystemDriveConstants.chatDrive, label = "Chat")
val contactLabeledDrive =
    LabeledDrive(drive = SystemDriveConstants.contactDrive, label = "Contacts")
val profileLabeledDrive = LabeledDrive(drive = SystemDriveConstants.profileDrive, label = "Profile")
val feedLabeledDrive = LabeledDrive(drive = SystemDriveConstants.feedDrive, label = "Feed")
val momentsLabeledDrive = LabeledDrive(
    drive = TargetDrive(
        alias = Uuid.parse("a85f8562-6c74-4947-896b-619812cafccc"),
        type = Uuid.parse("4338d7d2-f217-486a-8790-a4982644c15f"),
    ),
    label = "Moments",
)

// Placeholder Vault drive — real GUIDs will replace these once the server feature ships.
val vaultLabeledDrive = LabeledDrive(
    drive = TargetDrive(
        alias = Uuid.parse("f47ac10b-58cc-4372-a567-0e02b2c3d479"),
//        type = Uuid.parse("00000000-0000-0000-0000-000000000001"), // Use this for Frodo or peter.parker.demo.rocks
        type = Uuid.parse("70e92f0f94d05f5c7dcd36466094f3a5"),
    ),
    label = "Vault",
)

// Stickers drive — modeled exactly on [vaultLabeledDrive] (alias + distinct type
// GUID). The user's saved "My Stickers" tray is one HomebaseFile per sticker on this
// dedicated, synced drive, so the library follows the user across devices via the
// existing sync engine. Like Vault/Moments this is an optional drive (not in
// [mandatorySyncDrives]); it is requested via the extend-permissions flow and mounted
// on demand the first time the user opens the sticker tray or saves a sticker.
//
// The alias is a stable random GUID; the type is the server-provisioned Stickers
// drive type GUID (matches the slot Vault uses for its drive type).
val stickerLabeledDrive = LabeledDrive(
    drive = TargetDrive(
        alias = Uuid.parse("3b9c5f2e-7a41-4d6b-9e0c-8f1a2b3c4d5e"),
        type = Uuid.parse("a8c64b10-7434-494b-8b8c-a2284bd643c8"),
    ),
    label = "Stickers",
)

// Location drive — modeled on [stickerLabeledDrive] (app-generated stable alias GUID +
// drive type GUID). Holds the user's encrypted location history: one file per device
// per UTC hour (see LocationTrackContent). Optional drive (not in [mandatorySyncDrives]);
// requested via the extend-permissions flow and mounted when the user activates the
// Location add-on.
//
// The type GUID is a placeholder until the server team provisions the real Location
// drive type (same caveat as Vault above).
val locationLabeledDrive = LabeledDrive(
    drive = TargetDrive(
        alias = Uuid.parse("2e191a14-8640-4ebc-b0c8-aaac913f6fa8"),
        type = Uuid.parse("9dbc3bf5-ca24-4d7d-98ca-6933af0ad491"),
    ),
    label = "Location",
)

// Default vault sections — stable UUIDs so re-running onboarding is idempotent
val vaultDefaultSections = listOf(
    Uuid.parse("6da3968b-0edf-41f0-a136-0492034030e2") to "Passports",
    Uuid.parse("0179aec4-b967-4fc9-a42c-5e9e140a4d0f") to "Driving Licenses",
    Uuid.parse("625e53e1-c9b3-425a-bd82-5e9dcfc56852") to "Credit Cards",
)

// Backward-compatible aliases — all existing consumers remain unaffected
val chatTargetDrive = chatLabeledDrive.drive
val contactTargetDrive = contactLabeledDrive.drive
val feedTargetDrive = feedLabeledDrive.drive

// App permissions required (general — excludes feed-specific permissions)
val appPermissions: List<AppPermissionType> =
    listOf(
        AppPermissionType.ReadConnections,
        AppPermissionType.ReadConnectionRequests,
        AppPermissionType.SendDataToOtherIdentitiesOnMyBehalf,
        AppPermissionType.ReceiveDataFromOtherIdentitiesOnMyBehalf,
        AppPermissionType.SendPushNotifications,
        AppPermissionType.SendIntroductions,
        // Required to write the owner's standard-profile attributes (in-app profile editor);
        // without it PUT /api/v2/profile/attributes returns 403. Paired with the ProfileDrive
        // Read grant below, which lets the editor read current values to prefill the form.
        AppPermissionType.ManageProfile,
    )

// Target drive access requests (general — excludes feed drive)
val targetDriveAccessRequest: List<TargetDriveAccessRequest> =
    listOf(
        TargetDriveAccessRequest(
            alias = chatTargetDrive.alias.toString(),
            type = chatTargetDrive.type.toString(),
            name = "Chat Drive",
            description = "Drive which contains all the chat messages",
            permissions =
                listOf(
                    DrivePermission.Read,
                    DrivePermission.Write,
                    DrivePermission.React
                )
        ),
        TargetDriveAccessRequest(
            alias = contactTargetDrive.alias.toString(),
            type = contactTargetDrive.type.toString(),
            name = " ",
            description = " ",
            permissions = listOf(DrivePermission.Read, DrivePermission.Write)
        ),
        // Read-only grant on the ProfileDrive so the in-app profile editor can read the owner's
        // current standard-profile attributes (id + versionTag + values) to prefill the form.
        // Writes don't need drive Write here — they go through the ManageProfile-gated
        // /api/v2/profile/attributes endpoint, not a direct drive upload.
        TargetDriveAccessRequest(
            alias = profileLabeledDrive.drive.alias.toString(),
            type = profileLabeledDrive.drive.type.toString(),
            name = "Profile Drive",
            description = "Drive which contains your profile information",
            permissions = listOf(DrivePermission.Read)
        ),
    )

val vaultTargetDriveAccessRequest: List<TargetDriveAccessRequest> = listOf(
    TargetDriveAccessRequest(
        alias = vaultLabeledDrive.drive.alias.toString(),
        type = vaultLabeledDrive.drive.type.toString(),
        name = vaultLabeledDrive.label,
        description = "Drive to store your personal documents",
        permissions = listOf(DrivePermission.Read, DrivePermission.Write),
    )
)

// Mandatory drives — always mounted; required for the chat app to function.
// Chat and Contacts power messaging.
// See ADDING_ADDON_APPS.md §"Mandatory vs Optional Drives" for the full model.
//
// Profile drive intentionally omitted: owner display name / avatar are loaded via
// the public unauthenticated `https://{odinId}/pub/profile` endpoint
// (PublicProfileProviderCached), not through the drive sync engine. Adding
// profileLabeledDrive here would start additional HTTP polling on every login for
// content nothing currently reads — wire it in only when a feature actually needs
// the profile drive synced into the local SQLDelight index.
val mandatorySyncDrives: List<LabeledDrive> =
    listOf(chatLabeledDrive, contactLabeledDrive /*, profileLabeledDrive */)

// Feed-specific permission config
val feedTargetDriveAccessRequest: List<TargetDriveAccessRequest> = listOf(
    TargetDriveAccessRequest(
        alias = feedTargetDrive.alias.toString(),
        type = feedTargetDrive.type.toString(),
        name = "Feed Drive",
        description = " ",
        permissions = listOf(DrivePermission.Read, DrivePermission.Write),
    ),
    TargetDriveAccessRequest(
        type = "8f448716e34cedf9014145e043ca6612",
        alias = Md5.toGuidId("public_channel_drive").toString(),
        name = " ",
        description = " ",
        permissions = listOf(
            DrivePermission.Read,
            DrivePermission.Write,
            DrivePermission.React,
            DrivePermission.Comment
        )

    ),
)

val feedAppPermissions: List<AppPermissionType> = listOf(
    AppPermissionType.ManageFeed,
    AppPermissionType.PublishStaticContent,
    AppPermissionType.ReadCircleMembers,
    AppPermissionType.ReadWhoIFollow,
    AppPermissionType.ReadMyFollowers
)

fun getFeedPermissionExtensionConfig(): PermissionExtensionConfig {
    return PermissionExtensionConfig(
        appId = AppConfig.APP_ID,
        appName = AppConfig.APP_NAME,
        drives = feedTargetDriveAccessRequest,
        permissions = feedAppPermissions,
        returnUrl = ::returnUrl
    )
}

// Moments-specific permission config — drive-only, no extra app permissions.
// React is required for the moment + comment reaction toggle endpoint
// (`/group-reactions/toggle`), which the server gates on DrivePermission.React.
val momentsTargetDriveAccessRequest: List<TargetDriveAccessRequest> = listOf(
    TargetDriveAccessRequest(
        alias = momentsLabeledDrive.drive.alias.toString(),
        type = momentsLabeledDrive.drive.type.toString(),
        name = "Moments Drive",
        description = "Drive which contains your saved moments",
        permissions = listOf(
            DrivePermission.Read,
            DrivePermission.Write,
            DrivePermission.React,
        ),
    ),
)

fun getMomentsPermissionExtensionConfig(): PermissionExtensionConfig {
    return PermissionExtensionConfig(
        appId = AppConfig.APP_ID,
        appName = AppConfig.APP_NAME,
        drives = momentsTargetDriveAccessRequest,
        permissions = emptyList(),
        returnUrl = ::returnUrl,
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
            permissions = listOf(DrivePermission.Write, DrivePermission.React)
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
        // needsAllConnected = true,
        returnUrl = ::returnUrl
    )
}

fun getVaultPermissionExtensionConfig(): PermissionExtensionConfig {
    return PermissionExtensionConfig(
        appId = AppConfig.APP_ID,
        appName = AppConfig.APP_NAME,
        drives = vaultTargetDriveAccessRequest,
        permissions = emptyList(),
        returnUrl = ::returnUrl
    )
}

// Stickers-specific permission config — drive-only, no extra app permissions.
// Mirrors the Vault/Moments optional-drive permission shape; the Stickers drive is
// mounted on demand (not in [mandatorySyncDrives]) the first time the user opens the
// sticker tray or saves a sticker.
val stickerTargetDriveAccessRequest: List<TargetDriveAccessRequest> = listOf(
    TargetDriveAccessRequest(
        alias = stickerLabeledDrive.drive.alias.toString(),
        type = stickerLabeledDrive.drive.type.toString(),
        name = stickerLabeledDrive.label,
        description = "Drive to store your saved stickers",
        permissions = listOf(DrivePermission.Read, DrivePermission.Write),
    )
)

// Location-specific permission config — drive-only, no extra app permissions.
// Mirrors the Vault/Moments optional-drive permission shape.
val locationTargetDriveAccessRequest: List<TargetDriveAccessRequest> = listOf(
    TargetDriveAccessRequest(
        alias = locationLabeledDrive.drive.alias.toString(),
        type = locationLabeledDrive.drive.type.toString(),
        name = "Location Drive",
        description = "Drive which contains your encrypted location history",
        permissions = listOf(DrivePermission.Read, DrivePermission.Write),
    )
)

fun getLocationPermissionExtensionConfig(): PermissionExtensionConfig {
    return PermissionExtensionConfig(
        appId = AppConfig.APP_ID,
        appName = AppConfig.APP_NAME,
        drives = locationTargetDriveAccessRequest,
        permissions = emptyList(),
        returnUrl = ::returnUrl,
    )
}

fun getStickerPermissionExtensionConfig(): PermissionExtensionConfig {
    return PermissionExtensionConfig(
        appId = AppConfig.APP_ID,
        appName = AppConfig.APP_NAME,
        drives = stickerTargetDriveAccessRequest,
        permissions = emptyList(),
        returnUrl = ::returnUrl,
    )
}
