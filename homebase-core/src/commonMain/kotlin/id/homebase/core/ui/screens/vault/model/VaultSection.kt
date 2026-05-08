@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault.model

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Immutable
data class VaultSection(
    val sectionId: Uuid,
    val fileId: Uuid,
    val title: String,
    val sortOrder: Int,
    val entries: List<VaultEntry> = emptyList(),
    val isFirst: Boolean = false,
    val isLast: Boolean = false,
    val versionTag: Uuid? = null,
    val keyHeader: KeyHeader? = null,
)

fun Pair<HomebaseFile, VaultSectionContent>.toVaultSection(
    entries: List<VaultEntry> = emptyList(),
    isFirst: Boolean = false,
    isLast: Boolean = false,
): VaultSection {
    val (file, content) = this
    return VaultSection(
        sectionId = file.fileMetadata.appData.uniqueId ?: file.fileId,
        fileId = file.fileId,
        title = content.title,
        sortOrder = content.sortOrder,
        entries = entries,
        isFirst = isFirst,
        isLast = isLast,
        versionTag = file.fileMetadata.versionTag,
        keyHeader = file.keyHeader,
    )
}

/**
 * Maps a [HomebaseFile] to a [VaultSectionContent], or returns null if the file
 * has no content or the content cannot be parsed.
 */
fun HomebaseFile.toVaultSectionModel(): VaultSectionContent? {
    val contentJson = fileMetadata.appData.content ?: return null
    return try {
        OdinSystemSerializer.deserialize<VaultSectionContent>(contentJson)
    } catch (e: Exception) {
        null
    }
}
