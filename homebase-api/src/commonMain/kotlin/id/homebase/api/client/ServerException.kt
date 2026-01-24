package id.homebase.api.client

class ServerException(
    status: Int,
    correlationId: String?,
    problem: ProblemDetails?
) : OdinApiException(status, "Server error (status=$status)", correlationId, problem)