package id.homebase.api.client.connections

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

@Serializable
data class IntroductionGroup(
    val recipients: List<OdinId>,
    val message: String? = null
)