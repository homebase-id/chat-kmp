package id.homebase.core.avatars

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId
import id.homebase.core.image.HomebaseImageData

@Immutable
data class ConversationAvatarModel(
    val type: Type,
    val imageData: HomebaseImageData? = null,
    val odinId: OdinId? = null,
    val initials: String? = null,
    val isGroup: Boolean = false
) {
    enum class Type {
        ConversationImage,
        Connection,
        Owner,
        GroupFallback
    }
}