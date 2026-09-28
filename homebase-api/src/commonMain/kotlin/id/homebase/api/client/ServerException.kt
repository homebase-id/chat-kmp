package id.homebase.api.client

class ServerException(
    status: Int,
    correlationId: String?,
    problem: ProblemDetails?
) : OdinApiException(
    status,
    buildString {
        append(problem?.title ?: "Server error")
        append(" (status=").append(status)
        problem?.errorCode()?.let { append(", errorCode=").append(it) }
        correlationId?.let { append(", correlationId=").append(it) }
        append(')')
    },
    correlationId,
    problem,
)
