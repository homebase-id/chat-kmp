package id.homebase.api.client

class ForbiddenException(
    problem: ProblemDetails? = null
) : OdinApiException(403, problem?.title ?: "Forbidden", problem = problem)

/**
 * True when [this] (or anything in its cause chain) is a server **permission denial** — a 403.
 * The server answered, and its answer is "this app token has no grant for that": a drive grant
 * revoked or narrowed in the owner console, an app re-authorized against a smaller set, a
 * circle/connection check that no longer passes. That is a state of the account, recoverable by
 * the user through the extend-permissions flow — not a defect in the client, and never a reason
 * to kill the process.
 *
 * Used by the platform uncaught-exception handlers exactly like [isTransientNetworkFailure] and
 * [isRecoverableServerConflict], for a 403 that leaks from a write on a scope with no
 * CoroutineExceptionHandler of its own (a bare `viewModelScope.launch`). Deliberately narrow:
 * 403 only. A 401 stays fatal — an invalid token is an auth-state problem the auth layer has to
 * act on, not something to keep running through. Walks the cause chain defensively (guards
 * against a cyclic chain).
 */
fun Throwable.isRecoverablePermissionFailure(): Boolean {
    val seen = HashSet<Throwable>()
    var cur: Throwable? = this
    while (cur != null && seen.add(cur)) {
        if (cur is ForbiddenException) return true
        cur = cur.cause
    }
    return false
}
