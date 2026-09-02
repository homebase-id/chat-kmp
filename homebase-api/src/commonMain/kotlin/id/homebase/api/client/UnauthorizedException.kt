package id.homebase.api.client

class UnauthorizedException :
    OdinApiException(401, "Unauthorized")

/**
 * True when [this] (or anything in its cause chain) is a 401. The response that produced it was
 * already routed to the auth layer by `OdinApiProviderBase.reportAuthOutcome`, so a caller
 * matching on this is deciding only what to do locally — never whether the token gets acted on.
 * Walks the cause chain defensively (guards against a cyclic chain), like
 * [isRecoverablePermissionFailure].
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
