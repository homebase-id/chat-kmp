package id.homebase.auth.login

import id.homebase.api.client.profile.ProfileCard
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun profileCard(name: String, status: String? = null) = ProfileCard(
    image = "https://frodo.digital/pub/image",
    givenName = null,
    familyName = null,
    status = status,
    name = name,
    bio = "",
    bioSummary = null,
    links = emptyList(),
    email = emptyList(),
    sameAs = emptyList(),
)

// Not advanceUntilIdle(): it stops as soon as the FOREGROUND work is idle, so it never runs a
// backgroundScope job — every assertion below would pass or fail for the wrong reason.
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() {
    advanceTimeBy(1_000)
    runCurrent()
}

/**
 * `profileCardOf` is a plain suspend lambda on the test dispatcher, which is what makes the
 * scheduler assertions meaningful — a `MockEngine` would park on Ktor's own dispatcher and let
 * every negative case pass vacuously. Each one asserts the call count for the same reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityPreviewResolverTest {

    @Test
    fun rapidInput_coalescesToOneLookup() = runTest {
        var calls = 0
        val resolver = IdentityPreviewResolver(
            scope = backgroundScope,
            profileCardOf = {
                calls++
                profileCard("Frodo Baggins")
            },
        )

        resolver.onInput("fro.digital")
        resolver.onInput("frod.digital")
        resolver.onInput("frodo.digital")
        settle()

        assertEquals(1, calls)
        assertEquals("frodo.digital", resolver.preview.value?.odinId?.domainName)
    }

    @Test
    fun invalidDomain_neverReachesTheNetwork() = runTest {
        var calls = 0
        val resolver = IdentityPreviewResolver(
            scope = backgroundScope,
            profileCardOf = {
                calls++
                profileCard("Frodo Baggins")
            },
        )

        resolver.onInput("frodo")
        resolver.onInput("")
        resolver.onInput("fr")
        settle()

        assertEquals(0, calls)
        assertNull(resolver.preview.value)
    }

    /**
     * iOS autofill injects a saved username and the user's keystrokes append to it, yielding e.g.
     * "frodo.baggins.demo.rocks" + "frodo.digital". That concatenation is a STRUCTURALLY VALID
     * domain (5 labels, limit 127), so `isValid` does not stop it — the debounce plus a failed
     * lookup is all that keeps it harmless. Pinned here: one request, no preview, no throw.
     */
    @Test
    fun autofillConcatenatedDomain_passesTheGateButYieldsNoPreview() = runTest {
        var calls = 0
        val resolver = IdentityPreviewResolver(
            scope = backgroundScope,
            profileCardOf = {
                calls++
                error("no such host")
            },
        )

        resolver.onInput("frodo.baggins.demo.rocksfrodo.digital")
        settle()

        assertEquals(1, calls)
        assertNull(resolver.preview.value)
    }

    @Test
    fun missingProfile_leavesPreviewNull() = runTest {
        var calls = 0
        val resolver = IdentityPreviewResolver(
            scope = backgroundScope,
            profileCardOf = {
                calls++
                null
            },
        )

        resolver.onInput("frodo.digital")
        settle()

        assertEquals(1, calls)
        assertNull(resolver.preview.value)
    }

    @Test
    fun resolvedProfile_populatesPreview() = runTest {
        val resolver = IdentityPreviewResolver(
            scope = backgroundScope,
            profileCardOf = { profileCard("Frodo Baggins", status = "Ring-bearer") },
        )

        resolver.onInput("frodo.digital")
        settle()

        val preview = resolver.preview.value
        assertEquals("frodo.digital", preview?.odinId?.domainName)
        assertEquals("Frodo Baggins", preview?.displayName)
        assertEquals("Ring-bearer", preview?.status)
    }

    @Test
    fun slowAbandonedDomain_doesNotOverwriteNewerPreview() = runTest {
        val slowResponse = CompletableDeferred<Unit>()
        val resolver = IdentityPreviewResolver(
            scope = backgroundScope,
            profileCardOf = { odinId ->
                if (odinId.domainName == "slow.digital") {
                    slowResponse.await()
                    profileCard("Slow Sam")
                } else {
                    profileCard("Fast Frodo")
                }
            },
        )

        resolver.onInput("slow.digital")
        settle()
        resolver.onInput("fast.digital")
        settle()
        slowResponse.complete(Unit)
        settle()

        assertEquals("fast.digital", resolver.preview.value?.odinId?.domainName)
        assertEquals("Fast Frodo", resolver.preview.value?.displayName)
    }

    @Test
    fun throwingLookup_leavesPreviewNullAndKeepsTheScopeAlive() = runTest {
        var calls = 0
        val resolver = IdentityPreviewResolver(
            scope = backgroundScope,
            profileCardOf = { odinId ->
                calls++
                if (odinId.domainName == "boom.digital") error("network down")
                profileCard("Frodo Baggins")
            },
        )

        resolver.onInput("boom.digital")
        settle()

        assertEquals(1, calls)
        assertNull(resolver.preview.value)

        resolver.onInput("frodo.digital")
        settle()

        assertEquals(2, calls)
        assertEquals("Frodo Baggins", resolver.preview.value?.displayName)
    }
}
