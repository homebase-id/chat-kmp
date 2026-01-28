package id.homebase.api.client

class ClientException(
    status: Int,
    val errorCode: OdinClientErrorCode = OdinClientErrorCode.UnhandledScenario,
    message: String,
    correlationId: String?,
    problem: ProblemDetails,
    cause: Throwable? = null
) : OdinApiException(status, message, correlationId, problem)