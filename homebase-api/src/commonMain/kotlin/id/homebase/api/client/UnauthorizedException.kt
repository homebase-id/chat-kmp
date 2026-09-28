package id.homebase.api.client

class UnauthorizedException(
    problem: ProblemDetails? = null
) : OdinApiException(401, "Unauthorized", problem?.correlationId(), problem)

/**
 * True when [this] (or anything in its cause chain) is a 401. Walks the cause chain defensively
 * (guards against a cyclic chain), like [isRecoverablePermissionFailure].
 */
fun Throwable.isUnauthorized(): Boolean {
    val seen = HashSet<Throwable>()
    var cur: Throwable? = this
    while (cur != null && seen.add(cur)) {
        if (cur is UnauthorizedException) return true
        cur = cur.cause
    }
    return false
}
