package id.homebase.api.client

class NotFoundException(
    problem: ProblemDetails? = null,
    /** False when something in front of the identity answered: it says nothing about the file. */
    val fromIdentity: Boolean = true,
) : OdinApiException(404, "Not found", problem?.correlationId(), problem)
