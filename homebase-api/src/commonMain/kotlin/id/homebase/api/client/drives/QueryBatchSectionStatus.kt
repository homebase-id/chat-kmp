package id.homebase.api.client.drives

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why a section of a query-batch-collection looks the way it does. An empty section caused by a
 * failure and an empty section meaning "nothing changed" are both empty — only this tells them apart.
 */
@Serializable
enum class QueryBatchSectionStatus {
    @SerialName("ok")
    Ok,

    @SerialName("budgetExhausted")
    BudgetExhausted,

    @SerialName("noReadGrant")
    NoReadGrant,

    @SerialName("driveNotFound")
    DriveNotFound,

    @SerialName("driveArchived")
    DriveArchived,

    @SerialName("error")
    Error;

    val isFailure: Boolean
        get() = this != Ok && this != BudgetExhausted
}
