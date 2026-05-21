package id.homebase.core.moments.services

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

@Serializable
data class MomentGroupContent(
    val version: Int,
    val title: String,
    val members: List<OdinId> = emptyList(),
)
