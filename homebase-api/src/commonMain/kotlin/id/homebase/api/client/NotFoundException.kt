package id.homebase.api.client

class NotFoundException(
    problem: ProblemDetails? = null
) : OdinApiException(404, "Not found", problem?.correlationId(), problem)
