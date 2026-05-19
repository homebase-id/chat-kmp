package id.homebase.core.ui.screens.vault.model

import kotlinx.serialization.Serializable

/**
 * JSON content stored in appData.content for vault files.
 */
@Serializable
data class VaultFileContent(
    val name: String,
    val label: String? = null,
    val notes: String? = null,
    val pdfPageCount: Int? = null,
)

@Serializable
data class VaultSectionContent(
    val title: String,
    val sortOrder: Int,
)

const val VAULT_FILE_TYPE = 5572
const val VAULT_SECTION_TYPE = 5573
