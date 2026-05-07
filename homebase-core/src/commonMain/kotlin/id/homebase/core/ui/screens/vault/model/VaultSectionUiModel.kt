@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault.model

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.core.ui.screens.vault.VaultFileItem
import id.homebase.core.ui.screens.vault.VaultSectionContent
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Immutable
data class VaultSectionUiModel(
    val sectionId: Uuid,
    val fileId: Uuid,
    val title: String,
    val sortOrder: Int,
    val entries: List<VaultFileItem>,
    val isFirst: Boolean = false,
    val isLast: Boolean = false,
    val versionTag: Uuid? = null,
    val keyHeader: KeyHeader? = null,
)

fun Pair<HomebaseFile, VaultSectionContent>.toSectionUiModel(
    entries: List<VaultFileItem>,
    isFirst: Boolean,
    isLast: Boolean,
): VaultSectionUiModel {
    val (file, content) = this
    return VaultSectionUiModel(
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
