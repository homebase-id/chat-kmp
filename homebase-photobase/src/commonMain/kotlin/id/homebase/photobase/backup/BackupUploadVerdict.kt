package id.homebase.photobase.backup

import id.homebase.api.client.ClientException
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.errorCodeEnumOrUnhandled
import id.homebase.api.client.isExistingFileWithUniqueId
import id.homebase.api.serialization.OdinSystemSerializer

/** An upload the system ran for us: only its status and body came back. A 4105 means the photo is there. */
fun backgroundUploadLanded(status: Int, body: String?): Boolean {
    if (status in 200..299) return true
    if (status != 400 || body.isNullOrBlank()) return false
    val problem = runCatching { OdinSystemSerializer.deserialize<ProblemDetails>(body) }.getOrNull() ?: return false
    return ClientException(
        status = status,
        errorCode = problem.errorCodeEnumOrUnhandled(),
        message = problem.title ?: "",
        correlationId = null,
        problem = problem,
    ).isExistingFileWithUniqueId()
}
