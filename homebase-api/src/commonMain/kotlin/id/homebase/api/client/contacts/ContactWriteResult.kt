package id.homebase.api.client.contacts

/**
 * Typed outcome of a CREATE or UPDATE write. The conflict-recovery loop branches on these instead
 * of catching exceptions — 409 and 404 are modeled as values, not throws. Transport/auth failures
 * (403, 5xx, …) still throw from the provider.
 */
sealed interface ContactWriteResult {
    /** 200 OK. */
    data class Ok(val body: ContactWriteResponse) : ContactWriteResult

    /** 409 Conflict. [conflict] carries the authoritative versionTag + current file header. */
    data class Conflict(val conflict: ContactWriteConflict) : ContactWriteResult

    /** 404 — no contact with that uniqueId (UPDATE only; CREATE never returns this). */
    data object NotFound : ContactWriteResult
}
