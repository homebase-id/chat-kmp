package id.homebase.api.client

class ServerException(
    status: Int,
    correlationId: String?,
    problem: ProblemDetails?
) : OdinApiException(status, problem?.title ?: "Server error", correlationId, problem)
