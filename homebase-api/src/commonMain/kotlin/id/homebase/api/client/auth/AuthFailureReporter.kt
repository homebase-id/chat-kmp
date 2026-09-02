package id.homebase.api.client.auth

/**
 * How a REST response reaches the auth layer. Declared here, in the module that issues the
 * requests, and implemented in the module that owns the session — `homebase-api` must not depend
 * on `homebase-core`/`homebase-common`, so the auth layer registers itself via
 * [CredentialsManager.setAuthFailureReporter] instead of being injected into all 21
 * [id.homebase.api.client.OdinApiProviderBase] subclasses.
 *
 * Only responses from the identity host we sent the token to are reported — see
 * `OdinApiProviderBase.reportAuthOutcome`.
 */
interface AuthFailureReporter {
    /** The identity host rejected this token. One call per 401 response. */
    fun onRestUnauthorized()

    /** The identity host accepted this token. One call per 2xx response. */
    fun onRestAuthorized()
}
