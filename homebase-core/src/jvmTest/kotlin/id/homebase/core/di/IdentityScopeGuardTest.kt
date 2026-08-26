package id.homebase.core.di

import id.homebase.core.session.IdentityScoped
import id.homebase.core.session.IdentitySessionQualifier
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.BeanDefinition
import org.koin.core.module.Module
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps the identity-scope rule from decaying back into a maintenance list.
 *
 * The bug this whole mechanism exists to prevent is a per-identity object registered as an
 * app-lifetime `single`, surviving a logout and serving the previous identity's state to the
 * next one. That is exactly what happened with the email screens: a stale address, a mailbox
 * reported as missing, and a drive mount that retried `400 InvalidDrive` once a second.
 *
 * Rather than scan source text, this reads Koin's own definition metadata — the same
 * [BeanDefinition]s the container resolves from — so it cannot drift from what actually gets
 * registered, whichever DSL spelling (`single`, `singleOf`, `single<T>`) was used.
 *
 * Note the marker belongs on the type that is REGISTERED. `single<SomeInterface> { Impl() }`
 * records `SomeInterface` as the primary type, so marking only `Impl` would slip past this.
 */
class IdentityScopeGuardTest {

    @Test
    fun `identity-scoped types are not registered app-lifetime`() {
        val offenders = allDefinitions()
            .filter { it.scopeQualifier != IdentitySessionQualifier }
            .filter { it.isIdentityScoped() }
            .map { "${it.primaryType.qualifiedName} (scope=${it.scopeQualifier})" }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "These types are marked IdentityScoped but registered app-lifetime, so they would " +
                "survive a logout and leak one identity's state into the next. Move them into " +
                "`scope(IdentitySessionQualifier) { scoped { ... } }`:\n  " +
                offenders.joinToString("\n  "),
        )
    }

    @Test
    fun `everything in the identity scope is marked IdentityScoped`() {
        // The inverse mistake: a type correctly placed in the scope but not marked, so nothing
        // stops a later refactor from quietly moving it back out to a `single`.
        val unmarked = allDefinitions()
            .filter { it.scopeQualifier == IdentitySessionQualifier }
            .filterNot { it.isIdentityScoped() }
            .map { it.primaryType.qualifiedName ?: it.primaryType.toString() }
            .sorted()

        assertTrue(
            unmarked.isEmpty(),
            "These types are registered in the identity scope but do not implement " +
                "IdentityScoped, so the guard above cannot protect them:\n  " +
                unmarked.joinToString("\n  "),
        )
    }

    private fun BeanDefinition<*>.isIdentityScoped(): Boolean =
        IdentityScoped::class.java.isAssignableFrom(primaryType.java) ||
            secondaryTypes.any { IdentityScoped::class.java.isAssignableFrom(it.java) }

    /**
     * Every definition across [allModules], following `includes(...)` into nested modules.
     *
     * Reads Koin-internal API deliberately. Asking the container what it actually registered
     * is the only way to state this rule without a regex over DSL spellings, and the cost is
     * contained: if Koin changes the shape, this test stops compiling — loudly, at build
     * time, in a test — rather than misreporting the app as safe.
     */
    @OptIn(KoinInternalApi::class)
    private fun allDefinitions(): List<BeanDefinition<*>> {
        val seen = mutableSetOf<Module>()
        val out = mutableListOf<BeanDefinition<*>>()

        fun walk(module: Module) {
            if (!seen.add(module)) return
            module.mappings.values.mapTo(out) { it.beanDefinition }
            module.includedModules.forEach(::walk)
        }

        allModules.forEach(::walk)
        return out
    }
}
