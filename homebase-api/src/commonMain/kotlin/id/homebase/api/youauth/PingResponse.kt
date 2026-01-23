package id.homebase.homebasekmppoc.lib.youauth

import kotlinx.serialization.Serializable

/** Response from the health/ping endpoint containing the server identity. */
@Serializable
data class PingResponse(
    val identity: String
)