@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.homebasekmppoc.prototype.lib.http

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object PublicPostsChannelDrive {
    val alias: Uuid = Uuid.parse("e8475dc46cb4b6651c2d0dbd0f3aad5f")
    val type: Uuid = Uuid.parse("8f448716e34cedf9014145e043ca6612")
}

//

//class PayloadPlayground(private val authenticated: AuthState.Authenticated) {
//
//    //
//
//    suspend fun getDrivesByType(type: Uuid): PagedResult<DriveDefinition> {
//
//        val params = GetDrivesByTypeRequest(
//            type.toString(),
//            1,
//            Int.MAX_VALUE
//        )
//
//        val uri = "/api/owner/v1/drive/mgmt/type?${params.toQueryString()}"
//        val client = OdinHttpClient(authenticated)
//        val drives = client.get<PagedResult<DriveDefinition>>(uri)
//
//        Logger.d("PayloadPlayground") { "Drives:" }
//        drives.results.forEach {
//            Logger.d("PayloadPlayground") { "  drive: Alias=${it.targetDriveInfo.alias} Type=${it.targetDriveInfo.type} Name=${it.name}" }
//        }
//
//        return drives
//    }
//
//    //
//
//    suspend fun getHeadersOnDrive(
//        appOrOwner: AppOrOwner,
//        driveAlias: Uuid,
//        driveType: Uuid,
//        fileState: FileState,
//        fileSystemType: FileSystemType = FileSystemType.Standard): List<SharedSecretEncryptedFileHeader> {
//        val qb = GetQueryBatchRequest(
//            alias = driveAlias,
//            type = driveType,
//            fileState = listOf(fileState),
//            maxRecords = 10000,
//            includeMetadataHeader = true
//        )
//
//        throw NotImplementedError("getHeadersOnDrive removed by todd; if we need this " +
//                "in the playground, then we can use the DriveQueryProvider")
//
////        val client = OdinHttpClient(authenticated)
////        val response = client.queryBatch(appOrOwner, qb, fileSystemType)
////        val result = response.searchResults
////        return result
//    }
//
//    //
//
//    suspend fun getVideosOnDrive(appOrOwner: AppOrOwner, driveAlias: Uuid, driveType: Uuid): List<PayloadWrapper> {
//        val headers = getHeadersOnDrive(appOrOwner, driveAlias, driveType, FileState.Active)
//        val comments = getHeadersOnDrive(appOrOwner, driveAlias, driveType, FileState.Active, FileSystemType.Comment)
//
//        Logger.d { "header count: ${headers.size}" }
//        Logger.d { "comment count: ${comments.size}" }
//
//        headers.forEach { header ->
//            Logger.d { "header payload count: ${header.fileMetadata.payloads?.size ?: 0}" }
//            header.fileMetadata.payloads?.forEach { payload ->
//                Logger.d { ("  " + payload.contentType) }
//            }
//        }
//
//        return buildList {
//            headers.forEach { header ->
//                header.fileMetadata.payloads?.forEach { payload ->
//                    if (payload.contentType?.contains("video") == true) {
//                        add(PayloadWrapper(authenticated, header, payload))
//                    }
//                }
//            }
//        }
//    }
//
//    //
//
//    suspend fun getFileHeader(
//        appOrOwner: AppOrOwner,
//        fileId: String,
//        alias: String,
//        type: String,
//        fileSystemType: String): SharedSecretEncryptedFileHeader
//    {
//        val uri = "/api/$appOrOwner/v1/drive/files/header?alias=$alias&type=$type&fileId=$fileId&xfst=$fileSystemType"
//
//        val client = OdinHttpClient(authenticated)
//        val result = client.get<SharedSecretEncryptedFileHeader>(uri)
//
//        return result
//    }
//
//    //
//
//}

//


