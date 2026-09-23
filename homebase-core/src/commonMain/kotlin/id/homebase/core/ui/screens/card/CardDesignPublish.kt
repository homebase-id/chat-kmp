package id.homebase.core.ui.screens.card

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UpdateFileResult
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.PermissionExtensionConfig
import id.homebase.api.youauth.SecurityContext
import id.homebase.api.youauth.drivePermissions
import id.homebase.core.config.AppConfig
import id.homebase.core.config.homePageDriveAccessRequest
import id.homebase.core.config.homePageLabeledDrive
import id.homebase.core.config.returnUrl
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal val THEME_ATTRIBUTE_TYPE: Uuid = Uuid.parse("8f7eb1c3-2fc7-2c0a-bf0c-ee09be588f26")
internal const val CARD_DESIGN_KEY = "cardDesign"
private const val MAX_PUBLISH_ATTEMPTS = 3

enum class CardDesignPublish { Published, NoTheme }

interface CardThemeFiles {
    suspend fun query(): List<HomebaseFile>

    /** Null when the file's versionTag was stale. */
    suspend fun update(request: UpdateFileByFileIdRequest): UpdateFileResult?
}

fun designAccessConfig(context: SecurityContext): PermissionExtensionConfig {
    val drive = homePageLabeledDrive.drive
    // The owner console replaces the app's grant on a drive with the requested one, so keep what it has.
    val permissions = (context.drivePermissions(drive.alias.toString(), drive.type.toString()) +
        DrivePermission.Read + DrivePermission.Write).distinct()
    return PermissionExtensionConfig(
        appId = AppConfig.APP_ID,
        appName = AppConfig.APP_NAME,
        drives = listOf(homePageDriveAccessRequest(permissions)),
        permissions = emptyList(),
        returnUrl = ::returnUrl,
    )
}

// The owner never having opened Home settings leaves no theme; the public card needs one, so none is invented.
internal suspend fun publishCardDesign(design: String, files: CardThemeFiles): CardDesignPublish {
    repeat(MAX_PUBLISH_ATTEMPTS) {
        val (file, content) = highestPriorityTheme(files.query()) ?: return CardDesignPublish.NoTheme
        if (files.update(themeDesignUpdate(file, content, design)) != null) return CardDesignPublish.Published
    }
    error("card design write contention exceeded $MAX_PUBLISH_ATTEMPTS attempts")
}

internal fun highestPriorityTheme(files: List<HomebaseFile>): Pair<HomebaseFile, JsonObject>? =
    files
        .filter { !it.fileMetadata.isEncrypted && THEME_ATTRIBUTE_TYPE in it.fileMetadata.appData.tags.orEmpty() }
        .mapNotNull { file ->
            val content = file.fileMetadata.appData.content ?: return@mapNotNull null
            val root = runCatching { cardJson.parseToJsonElement(content) as? JsonObject }.getOrNull()
            root?.let { file to it }
        }
        .minByOrNull { (_, root) -> (root["priority"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }

internal fun themeDesignUpdate(file: HomebaseFile, content: JsonObject, design: String): UpdateFileByFileIdRequest {
    val data = content["data"] as? JsonObject ?: JsonObject(emptyMap())
    val merged = JsonObject(content + ("data" to JsonObject(data + (CARD_DESIGN_KEY to JsonPrimitive(design)))))
    val metadata = file.fileMetadata
    val appData = metadata.appData
    return UpdateFileByFileIdRequest(
        driveId = SystemDriveConstants.homePageConfigDrive.alias,
        fileId = file.fileId,
        keyHeader = null,
        instructions = FileUpdateInstructionSet(
            transferIv = ByteArrayUtil.getRndByteArray(16),
            locale = UpdateLocale.Local,
            recipients = emptyList(),
            // No payload operations: the header and favicon images stay as they are.
            manifest = UpdateManifest.build(
                payloads = emptyList(),
                toDeletePayloads = null,
                thumbnails = emptyList(),
                generatePayloadIv = false,
            ),
        ),
        metadata = UploadFileMetadata(
            allowDistribution = file.serverMetadata.allowDistribution,
            isEncrypted = false,
            accessControlList = file.serverMetadata.accessControlList,
            appData = UploadAppFileMetaData(
                uniqueId = appData.uniqueId,
                tags = appData.tags,
                fileType = appData.fileType,
                dataType = appData.dataType,
                userDate = appData.userDate,
                groupId = appData.groupId,
                archivalStatus = appData.archivalStatus,
                content = merged.toString(),
                previewThumbnail = appData.previewThumbnail,
            ),
            versionTag = metadata.versionTag,
            ttl = metadata.ttl,
        ),
        payloads = emptyList(),
        thumbnails = emptyList(),
    )
}
