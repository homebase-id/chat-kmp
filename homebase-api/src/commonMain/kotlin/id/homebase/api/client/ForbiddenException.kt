package id.homebase.api.client

class ForbiddenException(
    problem: ProblemDetails? = null
) : OdinApiException(403, problem?.title ?: "Forbidden", problem = problem)