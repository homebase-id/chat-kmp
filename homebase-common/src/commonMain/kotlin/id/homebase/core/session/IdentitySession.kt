package id.homebase.core.session

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.koin.core.Koin
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope

/**
 * Marker for state that belongs to ONE logged-in identity and must not outlive it.
 *
 * Implementing this is a claim about lifetime, not behaviour: it says "when the user logs
 * out, I am garbage". The guard test asserts that nothing marked this way is registered as
 * an app-lifetime `single`, which is what keeps the rule from decaying into a checklist
 * somebody has to remember to update.
 *
 * Conversation, drive, contact, feed, moment, location, vault, sticker and email state is
 * identity-scoped. Platform plumbing that legitimately outlives a session — the database
 * manager, the HTTP client factory, [org.koin.core.Koin] itself, and CredentialsManager,
 * which is the thing that *clears* credentials — is not.
 */
interface IdentityScoped

/** Qualifier of the per-identity Koin scope. Scoped definitions declare `scope(IdentitySessionQualifier) { ... }`. */
val IdentitySessionQualifier: Qualifier = named("IdentitySession")

/**
 * Owns the lifetime of the per-identity Koin scope.
 *
 * Opened when auth reaches Authenticated, closed on logout. Closing drops every `scoped`
 * instance and fires its `onClose` callback, so the next login constructs fresh objects
 * instead of reusing reset ones. That is the whole point: forgetting to clear something
 * becomes impossible, because nothing survives to be stale.
 *
 * Replaces three overlapping mechanisms that each covered part of the surface — a
 * hand-maintained `reset()` list in AppModule (which ran at the *next* login, not at
 * logout, and skipped entirely on a warm relaunch), ad-hoc self-clearing on
 * `BackendEvent.SessionEnded`, and, for anything nobody remembered, nothing at all.
 */
class IdentitySessionScope(private val koin: Koin) {

    private val lock = SynchronizedObject()

    @Volatile
    private var current: Scope? = null

    @Volatile
    private var currentIdentity: String? = null

    /** The open scope, or null while logged out. */
    val scopeOrNull: Scope? get() = current?.takeIf { !it.closed }

    val isOpen: Boolean get() = scopeOrNull != null

    /** Domain of the identity the open scope belongs to, or null while logged out. */
    val identity: String? get() = if (isOpen) currentIdentity else null

    /**
     * Open the scope for [identity], closing any scope still open for a different one.
     *
     * Re-opening for the same identity is a no-op that returns the live scope: the
     * authenticated transition can run more than once per session (headless bootstrap
     * followed by [foreground promotion][id.homebase.core.auth.AuthConnectionCoordinator.promoteToForeground]),
     * and tearing down live services on the second pass would be a regression, not a reset.
     */
    fun open(identity: String): Scope = synchronized(lock) {
        val live = current?.takeIf { !it.closed }
        if (live != null && currentIdentity == identity) return@synchronized live

        if (live != null) {
            Logger.i(tag = TAG) { "identity changed ($currentIdentity -> $identity) — closing previous session scope" }
            closeLocked()
        }

        Logger.i(tag = TAG) { "opening session scope for $identity" }
        // Defensive: createScope throws ScopeAlreadyCreatedException on a duplicate id. That
        // needs an earlier open() to have half-failed, leaving a scope in the registry we no
        // longer hold — rare, but the consequence would be an unrecoverable login.
        koin.deleteScope(scopeId(identity))
        return@synchronized koin.createScope(scopeId(identity), IdentitySessionQualifier).also {
            current = it
            currentIdentity = identity
        }
    }

    /**
     * Destroy the scope and everything in it. Idempotent — logout can arrive by more than
     * one path (explicit sign-out, token expiry, an identity switch) and must not throw on
     * the second.
     */
    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        val live = current
        current = null
        currentIdentity = null
        if (live == null || live.closed) return
        Logger.i(tag = TAG) { "closing session scope ${live.id}" }
        live.close()
    }

    /**
     * The open scope, or a throw naming the caller. Resolving identity-scoped state while
     * logged out is a bug in the caller's lifecycle, not a condition to paper over with a
     * fallback instance that would immediately go stale.
     */
    fun requireScope(): Scope = scopeOrNull
        ?: error("No identity session is open — cannot resolve identity-scoped dependencies while logged out")

    private fun scopeId(identity: String) = "$SCOPE_ID_PREFIX$identity"

    private companion object {
        const val TAG = "IdentitySession"
        const val SCOPE_ID_PREFIX = "identity:"
    }
}

/** Resolve an identity-scoped dependency. Throws if no session is open — see [IdentitySessionScope.requireScope]. */
inline fun <reified T : Any> IdentitySessionScope.get(): T = requireScope().get()

/** Resolve an identity-scoped dependency, or null while logged out. */
inline fun <reified T : Any> IdentitySessionScope.getOrNull(): T? = scopeOrNull?.get()
