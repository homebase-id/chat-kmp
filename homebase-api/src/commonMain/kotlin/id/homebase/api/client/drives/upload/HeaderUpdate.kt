package id.homebase.api.client.drives.upload

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.crypto.ByteArrayUtil
import kotlin.uuid.Uuid

/**
 * [this] file's header, read decrypted, written again in place with [appData] changed by [change]: everything else as
 * read, its ACL included, so a shared file stays shared; its payloads stay. Guarded by the version it was read at, and
 * encrypted under the file's own key with a new IV.
 */
suspend fun HomebaseFile.headerUpdate(change: (UploadAppFileMetaData) -> UploadAppFileMetaData): UpdateFileByFileIdRequest {
    val read = fileMetadata.appData
    val keyHeader = keyHeader.withNewIv()
    val metadata = UploadFileMetadata(
        allowDistribution = serverMetadata.allowDistribution,
        isEncrypted = serverFileIsEncrypted,
        accessControlList = serverMetadata.accessControlList,
        appData = change(
            UploadAppFileMetaData(
                uniqueId = read.uniqueId,
                tags = read.tags,
                fileType = read.fileType,
                dataType = read.dataType,
                userDate = read.userDate,
                groupId = read.groupId,
                archivalStatus = read.archivalStatus,
                content = read.content,
                previewThumbnail = read.previewThumbnail,
            )
        ),
        referencedFile = fileMetadata.referencedFile,
        versionTag = fileMetadata.versionTag,
        ttl = fileMetadata.ttl,
    )
    return UpdateFileByFileIdRequest(
        driveId = driveId,
        fileId = fileId,
        keyHeader = keyHeader,
        instructions = FileUpdateInstructionSet(
            transferIv = ByteArrayUtil.getRndByteArray(16),
            locale = UpdateLocale.Local,
            recipients = emptyList(),
            manifest = UpdateManifest.build(),
        ),
        metadata = if (serverFileIsEncrypted) metadata.encryptContent(keyHeader) else metadata,
    )
}

/** [tags] with [tag] added or taken out; null when there are none left. */
fun List<Uuid>?.withTag(tag: Uuid, on: Boolean): List<Uuid>? {
    val now = orEmpty().filter { it != tag } + listOfNotNull(tag.takeIf { on })
    return now.takeIf { it.isNotEmpty() }
}
