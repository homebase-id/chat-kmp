package id.homebase.api.client

sealed class OdinApiException(
    val status: Int,
    message: String,
    val correlationId: String? = null,
    val problem: ProblemDetails? = null
) : RuntimeException(message)
