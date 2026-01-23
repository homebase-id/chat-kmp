package id.homebase.homebasekmppoc.prototype.lib.drives

import kotlinx.serialization.Serializable

@Serializable
data class GetDrivesRequest (
    var pageNumber: Int,
    var pageSize: Int
)
