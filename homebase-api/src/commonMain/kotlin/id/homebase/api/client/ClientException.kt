package id.homebase.api.client

class ClientException(
    status: Int,
    val errorCode: OdinClientErrorCode = OdinClientErrorCode.UnhandledScenario,
    message: String,
    correlationId: String?,
    problem: ProblemDetails,
    cause: Throwable? = null
) : OdinApiException(status, message, correlationId, problem)

/**
 * True when [this] (or anything in its cause chain) is a **recoverable optimistic-concurrency
 * conflict** — a server 400 with [OdinClientErrorCode.VersionTagMismatch] (covers both "Missing"
 * and "Mismatching version tag"). The update didn't apply because the file's versionTag advanced
 * (a concurrent edit, or a reconcile racing another write); local state stays consistent and
 * drive-sync brings the authoritative version. It is never a reason to crash.
 *
 * Used by the platform uncaught-exception handlers — exactly like [isTransientNetworkFailure] — so
 * a VersionTagMismatch that leaks from a write on a scope without its own CoroutineExceptionHandler
 * (e.g. a bare `viewModelScope.launch`) is recorded as a non-fatal instead of killing the process
 * (#1008). Narrow by design (only VersionTagMismatch) so a genuine bad-request bug still crashes.
 * Walks the cause chain defensively (guards against a cyclic chain).
 */
fun Throwable.isRecoverableServerConflict(): Boolean {
    val seen = HashSet<Throwable>()
    var cur: Throwable? = this
    while (cur != null && seen.add(cur)) {
        if (cur is ClientException && cur.errorCode == OdinClientErrorCode.VersionTagMismatch) return true
        cur = cur.cause
    }
    return false
}