package id.homebase.api.client.auth

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId

@Immutable
data class OwnerSession(
    val odinId: OdinId,
    val displayName: String?,
    val firstName: String?,
    val surName: String?,
    val profileImageFileId: String?,
    val profileImageFileKey: String?,
    val profileImagePreviewThumbnail: String?, // base64 content
    val profileImageLastModified: Long?,
    val status: String?
)