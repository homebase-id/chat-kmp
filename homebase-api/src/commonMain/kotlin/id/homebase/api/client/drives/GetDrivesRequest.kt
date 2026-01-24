package id.homebase.api.client.drives

import kotlinx.serialization.Serializable

@Serializable
data class GetDrivesRequest (
    var pageNumber: Int,
    var pageSize: Int
)
