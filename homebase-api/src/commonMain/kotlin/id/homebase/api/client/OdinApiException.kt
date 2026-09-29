package id.homebase.api.client

sealed class OdinApiException(
    val status: Int,
    title: String,
    val correlationId: String? = null,
    val problem: ProblemDetails? = null
) : RuntimeException(withServerDetail(title, status, correlationId, problem))

private fun withServerDetail(
    title: String,
    status: Int,
    correlationId: String?,
    problem: ProblemDetails?,
): String {
    if (status <= 0) return title
    return buildString {
        append(title)
        append(" (status=").append(status)
        problem?.errorCode()?.let { append(", errorCode=").append(it) }
        (correlationId ?: problem?.correlationId())?.let { append(", correlationId=").append(it) }
        append(')')
    }
}
