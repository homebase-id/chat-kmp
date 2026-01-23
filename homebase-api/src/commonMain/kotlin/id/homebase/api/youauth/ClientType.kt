package id.homebase.homebasekmppoc.lib.youauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Client type for YouAuth authorization. */
@Serializable
enum class ClientType {
    @SerialName("unknown") unknown,
    @SerialName("app") app,
    @SerialName("domain") domain
}
