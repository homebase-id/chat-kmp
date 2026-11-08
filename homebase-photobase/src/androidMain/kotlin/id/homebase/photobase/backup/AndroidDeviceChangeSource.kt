package id.homebase.photobase.backup

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaStore generations on API 30+, full listing below that. An asset id is `volume/kind/rowId`.
 * Checkpoint: one entry per volume, `version|highest generation seen`.
 */
class AndroidDeviceChangeSource(private val context: Context) : DeviceChangeSource {
    private val resolver: ContentResolver get() = context.contentResolver

    private enum class Kind(val tag: String, val mediaType: BackupMediaType) {
        Images("images", BackupMediaType.Image),
        Video("video", BackupMediaType.Video),
    }

    override suspend fun changes(checkpoint: Map<String, String>, albumIds: Set<String>, wantCurrentIds: Boolean): DeviceChanges =
        withContext(Dispatchers.IO) {
            val assets = mutableListOf<DeviceAsset>()
            val next = mutableMapOf<String, String>()
            var isFull = false
            for (volume in volumes()) {
                val version = versionOf(volume)
                val stored = checkpoint[volume]?.split(SEPARATOR)
                val sinceGeneration = stored?.takeIf { it.size == 2 && it[0] == version && version != null }?.get(1)?.toLongOrNull()
                if (sinceGeneration == null) isFull = true
                var highest = sinceGeneration ?: 0L
                for (kind in Kind.entries) {
                    query(volume, kind, albumIds, sinceGeneration) { asset, added, modified ->
                        highest = maxOf(highest, added, modified)
                        assets += asset
                    }
                }
                if (version != null) next[volume] = "$version$SEPARATOR$highest"
            }
            val currentIds = if (wantCurrentIds || isFull) allIds(albumIds) else null
            DeviceChanges(assets, emptyList(), next, currentIds)
        }

    override suspend fun export(assetId: String, mediaType: BackupMediaType): ExportedAsset = withContext(Dispatchers.IO) {
        val parts = assetId.split("/")
        val kind = Kind.entries.firstOrNull { it.tag == parts.getOrNull(1) }
            ?: throw AssetPermanentlyUnavailableException("unknown asset id $assetId")
        val rowId = parts[2].toLong()
        val base = contentUri(parts[0], kind)
        val uri = ContentUris.withAppendedId(base, rowId)
        val projection = arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME)
        val (contentType, name) = resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty() else null
        } ?: throw AssetPermanentlyUnavailableException("asset gone")
        ExportedAsset(listOf(ExportedPart(ExportedPartRole.Primary, originalUri(uri).toString(), contentType, isTemporary = false)), name)
    }

    override suspend fun albums(): List<DeviceAlbum> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, String>()
        for (volume in volumes()) {
            for (kind in Kind.entries) {
                val projection = arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                resolver.query(contentUri(volume, kind), projection, "${MediaStore.MediaColumns.IS_PENDING} = 0", null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        found.getOrPut(id) { cursor.getString(1).orEmpty() }
                    }
                }
            }
        }
        found.map { (id, name) -> DeviceAlbum(id, name) }
    }

    private fun volumes(): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.getExternalVolumeNames(context) else setOf(MediaStore.VOLUME_EXTERNAL)

    private fun versionOf(volume: String): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getVersion(context, volume) else null

    private fun contentUri(volume: String, kind: Kind): Uri = when (kind) {
        Kind.Images -> MediaStore.Images.Media.getContentUri(volume)
        Kind.Video -> MediaStore.Video.Media.getContentUri(volume)
    }

    // Without this Android redacts GPS from the EXIF; it needs ACCESS_MEDIA_LOCATION, and refuses without it.
    private fun originalUri(uri: Uri): Uri {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri
    }

    private fun scopeSelection(albumIds: Set<String>): Pair<String, List<String>> {
        if (albumIds.isNotEmpty()) {
            val marks = albumIds.joinToString(",") { "?" }
            return "${MediaStore.MediaColumns.BUCKET_ID} IN ($marks)" to albumIds.toList()
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to listOf("$CAMERA_PATH%")
        } else {
            "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?" to listOf(CAMERA_BUCKET)
        }
    }

    private fun query(
        volume: String,
        kind: Kind,
        albumIds: Set<String>,
        sinceGeneration: Long?,
        onRow: (asset: DeviceAsset, generationAdded: Long, generationModified: Long) -> Unit,
    ) {
        val withGenerations = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.BUCKET_ID)
            if (withGenerations) {
                add(MediaStore.MediaColumns.GENERATION_ADDED)
                add(MediaStore.MediaColumns.GENERATION_MODIFIED)
            }
        }.toTypedArray()
        val (scope, scopeArgs) = scopeSelection(albumIds)
        var selection = "$scope AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        var args = scopeArgs
        if (sinceGeneration != null && withGenerations) {
            selection += " AND (${MediaStore.MediaColumns.GENERATION_MODIFIED} > ? OR ${MediaStore.MediaColumns.GENERATION_ADDED} > ?)"
            args = args + sinceGeneration.toString() + sinceGeneration.toString()
        }
        resolver.query(contentUri(volume, kind), projection, selection, args.toTypedArray(), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val rowId = cursor.getLong(0)
                val addedSeconds = cursor.getLong(1)
                val modifiedSeconds = cursor.getLong(2)
                val takenMs = cursor.getLong(3)
                val asset = DeviceAsset(
                    id = "$volume/${kind.tag}/$rowId",
                    mediaType = kind.mediaType,
                    createdAtMs = if (takenMs > 0) takenMs else addedSeconds * 1000,
                    modifiedAtMs = modifiedSeconds * 1000,
                    sizeBytes = cursor.getLong(4),
                    albumIds = listOfNotNull(cursor.getString(5)),
                )
                onRow(asset, if (withGenerations) cursor.getLong(6) else 0, if (withGenerations) cursor.getLong(7) else 0)
            }
        }
    }

    private fun allIds(albumIds: Set<String>): Set<String> {
        val ids = mutableSetOf<String>()
        val (scope, args) = scopeSelection(albumIds)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "$scope AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
        }
        for (volume in volumes()) {
            for (kind in Kind.entries) {
                resolver.query(contentUri(volume, kind), arrayOf(MediaStore.MediaColumns._ID), queryArgs, null)?.use { cursor ->
                    while (cursor.moveToNext()) ids += "$volume/${kind.tag}/${cursor.getLong(0)}"
                }
            }
        }
        return ids
    }

    private companion object {
        const val SEPARATOR = "|"
        const val CAMERA_BUCKET = "Camera"
        const val CAMERA_PATH = "DCIM/"
    }
}
