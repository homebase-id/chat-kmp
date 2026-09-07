package id.homebase.core.di

import id.homebase.core.session.IdentityScoped
import id.homebase.core.session.IdentitySessionQualifier
import id.homebase.core.session.IdentitySessionScope
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.BeanDefinition
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
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


    @Test
    fun `every scoped definition can actually reach its dependencies from the scope`() {
        // The guards above check WHERE things are registered. They cannot catch the trap that
        // makes placement matter: a definition that lives in the scope but depends on something
        // only reachable from root — or, the way it usually happens, a definition left at root
        // whose dependency moved into the scope. Koin rebinds the resolution context to root on
        // linked-scope fallback (see ScopeResolutionMechanicsTest), so that failure is a runtime
        // throw on whatever screen touches it first, not a build error.
        //
        // So: resolve every scoped definition the way the app does and look for a MISSING
        // DEFINITION specifically. Other construction failures are expected here — plenty of
        // these objects need a real database, filesystem or platform service that a headless
        // test has no business providing — and are deliberately tolerated. A missing definition
        // is never environmental.
        val koin = koinApplication(createEagerInstances = false) { modules(allModules) }.koin
        val session = IdentitySessionScope(koin)
        val scope = session.open("frodo.dotyou.cloud")

        val unreachable = mutableListOf<String>()
        var constructed = 0

        allDefinitions()
            .filter { it.scopeQualifier == IdentitySessionQualifier }
            .forEach { definition ->
                try {
                    scope.get<Any>(definition.primaryType, definition.qualifier, null)
                    constructed++
                } catch (t: Throwable) {
                    val missing = generateSequence(t) { it.cause }
                        .filterIsInstance<NoDefinitionFoundException>()
                        .firstOrNull()
                    if (missing != null) {
                        unreachable += "${definition.primaryType.qualifiedName} -> ${missing.message}"
                    }
                }
            }

        session.close()
        koin.close()

        assertTrue(
            unreachable.isEmpty(),
            "These identity-scoped definitions depend on something they cannot resolve from the " +
                "identity scope. Usually the dependency is still registered app-lifetime, or a " +
                "consumer was left at root while its dependency moved:\n  " +
                unreachable.joinToString("\n  "),
        )
        // Guard the guard: if nothing constructs, the assertion above is vacuous.
        assertTrue(constructed > 0, "No scoped definition constructed — this check proved nothing")
    }


    @Test
    fun `no app-lifetime definition depends on identity-scoped state`() {
        // The other direction of the same trap, and the one that actually bit during the
        // migration: a definition left at ROOT that takes an identity-scoped type in its
        // constructor. Nothing above catches it — the consumer is not itself marked
        // IdentityScoped, and it is not in the scope, so neither placement guard applies. It
        // fails at runtime, on whichever screen resolves it first.
        //
        // Checked by reflecting constructor parameters rather than by instantiating: resolving
        // every app-lifetime definition in a test would start coroutines, open databases and
        // touch the filesystem. The cost is that `single<SomeInterface> { Impl() }` records the
        // interface as its primary type, so a dependency reachable only through an interface is
        // not seen here.
        //
        // The fix for a violation is usually not to move the consumer: something app-lifetime
        // that genuinely needs per-identity state should resolve it on demand through
        // IdentitySessionScope, as AppViewModel does for the moment draft.
        val scopedTypes = allDefinitions()
            .filter { it.scopeQualifier == IdentitySessionQualifier }
            .map { it.primaryType.java }
            .toSet()

        val violations = allDefinitions()
            .filter { it.scopeQualifier != IdentitySessionQualifier }
            .flatMap { definition ->
                val consumer = definition.primaryType
                val params = runCatching {
                    consumer.java.constructors.flatMap { it.parameterTypes.toList() }
                }.getOrDefault(emptyList())
                params.filter { it in scopedTypes }
                    .map { "${consumer.qualifiedName} depends on ${it.name}" }
            }
            .distinct()
            .sorted()

        assertTrue(
            violations.isEmpty(),
            "These app-lifetime definitions take identity-scoped types in their constructor, so " +
                "they cannot be constructed once that state lives in the scope — and holding one " +
                "would pin a single identity's state for the life of the process. Resolve it on " +
                "demand through IdentitySessionScope instead:\n  " +
                violations.joinToString("\n  "),
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
