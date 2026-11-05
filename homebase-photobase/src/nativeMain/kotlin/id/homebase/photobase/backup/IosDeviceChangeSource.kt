package id.homebase.photobase.backup

import id.homebase.api.file.FileOperationsProvider
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSURL
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeAny
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetMediaSubtypePhotoLive
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceType
import platform.Photos.PHAssetResourceTypeFullSizePairedVideo
import platform.Photos.PHAssetResourceTypeFullSizePhoto
import platform.Photos.PHAssetResourceTypeFullSizeVideo
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo
import platform.Photos.PHFetchResult
import platform.Photos.PHObjectTypeAsset
import platform.Photos.PHPersistentChangeFetchResult
import platform.Photos.PHPersistentChangeToken
import platform.Photos.PHPhotoLibrary
import platform.UniformTypeIdentifiers.UTType
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PHPersistentChangeToken, falling back to listing everything when the token is missing or expired.
 * A selected-album scope always lists: change history doesn't say which albums an asset is in.
 */
class IosDeviceChangeSource(private val fileOps: FileOperationsProvider) : DeviceChangeSource {

    private val library get() = PHPhotoLibrary.sharedPhotoLibrary()

    override suspend fun changes(checkpoint: Map<String, String>, albumIds: Set<String>, wantCurrentIds: Boolean): DeviceChanges =
        withContext(Dispatchers.Default) {
            val newToken = encode(library.currentChangeToken)
            val delta = if (albumIds.isEmpty()) checkpoint[TOKEN_KEY]?.let(::decode)?.let(::deltaSince) else null
            if (delta != null) {
                DeviceChanges(
                    assets((delta.inserted + delta.updated) - delta.deleted), delta.deleted.toList(), mapOf(TOKEN_KEY to newToken),
                    currentIds = if (wantCurrentIds) listAll(albumIds).mapTo(mutableSetOf()) { it.id } else null,
                )
            } else {
                val all = listAll(albumIds)
                DeviceChanges(all, emptyList(), mapOf(TOKEN_KEY to newToken), currentIds = all.mapTo(mutableSetOf()) { it.id })
            }
        }

    override suspend fun export(assetId: String, mediaType: BackupMediaType): ExportedAsset = withContext(Dispatchers.Default) {
        val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(assetId), null).firstObject as? PHAsset
            ?: throw AssetPermanentlyUnavailableException("asset gone")
        val resources = PHAssetResource.assetResourcesForAsset(asset).filterIsInstance<PHAssetResource>()
        val parts = when (mediaType) {
            BackupMediaType.Image -> listOfNotNull(resources.pick(PHAssetResourceTypeFullSizePhoto, PHAssetResourceTypePhoto)?.let { ExportedPartRole.Primary to it })
            BackupMediaType.Video -> listOfNotNull(resources.pick(PHAssetResourceTypeFullSizeVideo, PHAssetResourceTypeVideo)?.let { ExportedPartRole.Primary to it })
            BackupMediaType.LivePhoto -> listOfNotNull(
                resources.pick(PHAssetResourceTypeFullSizePhoto, PHAssetResourceTypePhoto)?.let { ExportedPartRole.Primary to it },
                resources.pick(PHAssetResourceTypeFullSizePairedVideo, PHAssetResourceTypePairedVideo)?.let { ExportedPartRole.PairedVideo to it },
            )
        }
        val expected = if (mediaType == BackupMediaType.LivePhoto) 2 else 1
        if (parts.size != expected) throw AssetPermanentlyUnavailableException("asset has no exportable original")
        val mayDownload = currentCoroutineContext()[NoCloudDownloads] == null
        val exported = mutableListOf<ExportedPart>()
        try {
            for ((role, resource) in parts) {
                val path = fileOps.createUploadTempPath("photo_export_", ".bin")
                write(resource, path, mayDownload)
                exported += ExportedPart(role, path, contentTypeOf(resource), isTemporary = true)
            }
        } catch (e: Throwable) {
            exported.forEach { runCatching { fileOps.deleteTempFile(it.path) } }
            throw e
        }
        ExportedAsset(exported, parts.first().second.originalFilename)
    }

    override suspend fun albums(): List<DeviceAlbum> = withContext(Dispatchers.Default) {
        val albums = PHAssetCollection.fetchAssetCollectionsWithType(PHAssetCollectionTypeAlbum, PHAssetCollectionSubtypeAny, null)
        buildList {
            for (index in 0 until albums.count.toInt()) {
                val album = albums.objectAtIndex(index.toULong()) as PHAssetCollection
                add(DeviceAlbum(album.localIdentifier, album.localizedTitle.orEmpty()))
            }
        }
    }

    private class Delta(val inserted: Set<String>, val updated: Set<String>, val deleted: Set<String>)

    private fun deltaSince(token: PHPersistentChangeToken): Delta? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val result: PHPersistentChangeFetchResult = library.fetchPersistentChangesSinceToken(token, error.ptr) ?: return null
        val inserted = mutableSetOf<String>()
        val updated = mutableSetOf<String>()
        val deleted = mutableSetOf<String>()
        var complete = true
        result.enumerateChangesWithBlock { change, _ ->
            val details = change?.changeDetailsForObjectType(PHObjectTypeAsset, error.ptr)
            if (details == null) {
                complete = false
            } else {
                details.insertedLocalIdentifiers.mapTo(inserted) { it as String }
                details.updatedLocalIdentifiers.mapTo(updated) { it as String }
                details.deletedLocalIdentifiers.mapTo(deleted) { it as String }
            }
        }
        if (!complete) return null
        Delta(inserted, updated, deleted)
    }

    private fun assets(ids: Set<String>): List<DeviceAsset> {
        if (ids.isEmpty()) return emptyList()
        return PHAsset.fetchAssetsWithLocalIdentifiers(ids.toList(), null).toDeviceAssets()
    }

    private fun listAll(albumIds: Set<String>): List<DeviceAsset> {
        if (albumIds.isEmpty()) return PHAsset.fetchAssetsWithOptions(null).toDeviceAssets()
        val albums = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(albumIds.toList(), null)
        val assets = linkedMapOf<String, DeviceAsset>()
        for (index in 0 until albums.count.toInt()) {
            val album = albums.objectAtIndex(index.toULong()) as PHAssetCollection
            PHAsset.fetchAssetsInAssetCollection(album, null).toDeviceAssets().forEach { asset ->
                val known = assets[asset.id]
                assets[asset.id] = asset.copy(albumIds = known?.albumIds.orEmpty() + album.localIdentifier)
            }
        }
        return assets.values.toList()
    }

    private fun PHFetchResult.toDeviceAssets(): List<DeviceAsset> = buildList(count.toInt()) {
        for (index in 0 until count.toInt()) {
            val asset = objectAtIndex(index.toULong()) as PHAsset
            val mediaType = when {
                asset.mediaType == PHAssetMediaTypeVideo -> BackupMediaType.Video
                asset.mediaType != PHAssetMediaTypeImage -> continue
                asset.mediaSubtypes and PHAssetMediaSubtypePhotoLive != 0uL -> BackupMediaType.LivePhoto
                else -> BackupMediaType.Image
            }
            add(
                DeviceAsset(
                    id = asset.localIdentifier,
                    mediaType = mediaType,
                    createdAtMs = ((asset.creationDate?.timeIntervalSince1970 ?: 0.0) * 1000).toLong(),
                    modifiedAtMs = ((asset.modificationDate?.timeIntervalSince1970 ?: 0.0) * 1000).toLong(),
                    // PhotoKit has no public size; the ledger compares modifiedAt, which is enough.
                    sizeBytes = 0,
                ),
            )
        }
    }

    private fun List<PHAssetResource>.pick(preferred: PHAssetResourceType, fallback: PHAssetResourceType): PHAssetResource? =
        firstOrNull { it.type == preferred } ?: firstOrNull { it.type == fallback }

    private suspend fun write(resource: PHAssetResource, path: String, mayDownload: Boolean) = suspendCancellableCoroutine<Unit> { continuation ->
        val options = PHAssetResourceRequestOptions().apply { networkAccessAllowed = mayDownload }
        PHAssetResourceManager.defaultManager().writeDataForAssetResource(resource, NSURL.fileURLWithPath(path), options) { error ->
            when {
                error == null -> continuation.resume(Unit)
                // Any refusal, not one error code: a real failure still counts when a run that may download meets it.
                !mayDownload -> continuation.resumeWithException(AssetNotOnDeviceException())
                else -> continuation.resumeWithException(IllegalStateException("export failed: ${error.localizedDescription}"))
            }
        }
    }

    private fun contentTypeOf(resource: PHAssetResource): String =
        UTType.typeWithIdentifier(resource.uniformTypeIdentifier)?.preferredMIMEType ?: "application/octet-stream"

    private fun encode(token: PHPersistentChangeToken): String = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val data = NSKeyedArchiver.archivedDataWithRootObject(token, requiringSecureCoding = true, error = error.ptr)
            ?: error("could not archive change token: ${error.value?.localizedDescription}")
        data.base64EncodedStringWithOptions(0u)
    }

    private fun decode(encoded: String): PHPersistentChangeToken? = memScoped {
        val data = NSData.create(base64EncodedString = encoded, options = 0u) ?: return null
        val error = alloc<ObjCObjectVar<NSError?>>()
        NSKeyedUnarchiver.unarchivedObjectOfClass(PHPersistentChangeToken, fromData = data, error = error.ptr) as? PHPersistentChangeToken
    }

    private companion object {
        const val TOKEN_KEY = "ios.changeToken"
    }
}
