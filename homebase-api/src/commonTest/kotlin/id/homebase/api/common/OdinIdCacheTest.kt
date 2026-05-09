package id.homebase.api.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class OdinIdCacheTest {

    @Test
    fun sameStringProducesSameHash() {
        val a = OdinId("alice.test")
        val b = OdinId("alice.test")
        assertEquals(a.toHashId(), b.toHashId())
        assertEquals(a, b)
    }

    @Test
    fun differentDomainsProduceDifferentHashes() {
        val alice = OdinId("alice.test")
        val bob = OdinId("bob.test")
        assertNotEquals(alice.toHashId(), bob.toHashId())
    }

    @Test
    fun caseNormalization_producesEqualHash() {
        val lower = OdinId("alice.test")
        val mixed = OdinId("Alice.Test")
        val upper = OdinId("ALICE.TEST")
        assertEquals(lower.toHashId(), mixed.toHashId())
        assertEquals(lower.toHashId(), upper.toHashId())
        assertEquals(lower, mixed)
        assertEquals(lower, upper)
    }

    @Test
    fun hashIsDeterministicAcrossCalls() {
        val first = OdinId("stable.test").toHashId()
        val second = OdinId("stable.test").toHashId()
        val third = OdinId("stable.test").toHashId()
        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun hashIsValidUuid() {
        val hash = OdinId("alice.test").toHashId()
        assertTrue(hash != Uuid.NIL)
    }

    @Test
    fun domainNamePreservedCorrectly() {
        val odinId = OdinId("Alice.Test")
        assertEquals("alice.test", odinId.domainName)
    }

    @Test
    fun manyDistinctDomainsAllProduceUniqueHashes() {
        val domains = (1..50).map { OdinId("user$it.test") }
        val hashes = domains.map { it.toHashId() }.toSet()
        assertEquals(50, hashes.size)
    }

    @Test
    fun concurrentConstructionProducesConsistentResults() = runTest {
        val results = withContext(Dispatchers.Default) {
            (1..100).map {
                async { OdinId("concurrent.test").toHashId() }
            }.awaitAll()
        }
        val unique = results.toSet()
        assertEquals(1, unique.size, "All concurrent constructions should produce the same hash")
    }

    @Test
    fun concurrentConstructionOfManyDomains() = runTest {
        val domains = listOf("alice.test", "bob.test", "carol.test", "dave.test")
        val results = withContext(Dispatchers.Default) {
            (1..200).map { i ->
                async { OdinId(domains[i % domains.size]) }
            }.awaitAll()
        }

        val grouped = results.groupBy { it.domainName }
        assertEquals(4, grouped.size)

        grouped.forEach { (_, odinIds) ->
            val hashes = odinIds.map { it.toHashId() }.toSet()
            assertEquals(1, hashes.size, "Same domain must always produce same hash")
        }
    }

    @Test
    fun fromByteArrayRoundTrips() {
        val original = OdinId("roundtrip.test")
        val fromBytes = OdinId.fromByteArray(original.toByteArray())
        assertEquals(original, fromBytes)
        assertEquals(original.toHashId(), fromBytes.toHashId())
    }

    @Test
    fun toHashIdSuspendMatchesConstructorHash() = runTest {
        val domain = AsciiDomainName("suspend.test")
        val suspendHash = OdinId.toHashId(domain)
        val constructorHash = OdinId("suspend.test").toHashId()
        assertEquals(suspendHash, constructorHash)
    }

    @Test
    fun equalityUsesHashNotReference() {
        val a = OdinId("equals.test")
        val b = OdinId("equals.test")
        assertTrue(a !== b, "Different instances")
        assertTrue(a == b, "Equal by hash")
        assertEquals(a.hashCode(), b.hashCode())
    }
}
