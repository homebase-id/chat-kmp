package id.homebase.api.client.contacts

import id.homebase.api.client.profile.FakeFileOperationsProvider
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PublicProfileProviderCached.invalidateImage] is the single "this avatar changed" signal — every
 * publisher reaches it, and it must drop the cached bytes *and* publish a revision, or Coil repaints
 * the copy already in memory. Every other read stays on the cache.
 *
 * [PublicAvatarRevisions] is process-wide and deliberately has no reset, so each test uses its own
 * identity.
 */
class AvatarInvalidationTest {

    private val firstBytes = ByteArray(32) { it.toByte() }
    private val secondBytes = ByteArray(16) { 0x7F }

    private var requestCount = 0
    private var nextBytes = firstBytes

    private val provider = PublicProfileProviderCached(
        httpClient = HttpClient(
            MockEngine {
                requestCount++
                respond(nextBytes, HttpStatusCode.OK)
            }
        ),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        fileOperationsProvider = FakeFileOperationsProvider(
            Files.createTempDirectory("hb-avatar-invalidation-test").toString()
        ),
    )

    @Test
    fun invalidateImageDropsTheCachedAvatarAndRepeatReadsDoNot() = runBlocking {
        val odinId = OdinId("drops.me")

        assertContentEquals(firstBytes, provider.getPublicImage(odinId))
        assertEquals(1, requestCount)

        // Normal caching: an unrelated read seconds later must not hit the network again.
        assertContentEquals(firstBytes, provider.getPublicImage(odinId))
        assertEquals(1, requestCount, "a plain read must stay on the cache")

        nextBytes = secondBytes
        provider.invalidateImage(odinId)

        assertContentEquals(secondBytes, provider.getPublicImage(odinId))
        assertEquals(2, requestCount, "invalidation must force the next read to the host")

        assertContentEquals(secondBytes, provider.getPublicImage(odinId))
        assertEquals(2, requestCount, "caching must resume after a forced refresh")
    }

    @Test
    fun invalidateImagePublishesARevisionForThatIdentityOnly() = runBlocking {
        val odinId = OdinId("publishes.me")
        val other = OdinId("bystander.me")

        provider.getPublicImage(odinId)
        assertNull(
            PublicAvatarRevisions.revisionOf(odinId.domainName),
            "a plain read must not bust the avatar cache key",
        )

        provider.invalidateImage(odinId)

        assertNotNull(
            PublicAvatarRevisions.revisionOf(odinId.domainName),
            "invalidating an image must publish a revision for that identity",
        )
        assertNull(
            PublicAvatarRevisions.revisionOf(other.domainName),
            "invalidating one identity must not bust another's avatar",
        )
    }

    @Test
    fun invalidateProfileDoesNotPublishARevision() = runBlocking {
        val odinId = OdinId("cardonly.me")

        // A ProfileCard-only republish re-reads the card; the photo is untouched, so every avatar
        // on screen must keep its bytes.
        provider.invalidateProfile(odinId)

        assertNull(PublicAvatarRevisions.revisionOf(odinId.domainName))
    }

    @Test
    fun everyInvalidationPublishesAStrictlyLargerRevision() = runBlocking {
        val odinId = OdinId("monotonic.me")

        provider.invalidateImage(odinId)
        val first = assertNotNull(PublicAvatarRevisions.revisionOf(odinId.domainName))
        provider.invalidateImage(odinId)
        val second = assertNotNull(PublicAvatarRevisions.revisionOf(odinId.domainName))

        // A device clock stepping backwards must not revert to a key Coil already has bytes for —
        // that would silently disarm the refresh.
        assertTrue(
            second > first,
            "a second invalidation must publish a strictly larger revision ($second was not > $first)",
        )
    }

    @Test
    fun resyncInvalidatesBeforeItReachesTheContactRepository() = runBlocking {
        val odinId = OdinId("resync.me")
        val gateway = ContactInfoGateway(
            contactRepository = { throw RepositoryReached() },
            publicProfiles = provider,
        )

        assertContentEquals(firstBytes, gateway.avatarBytes(odinId))
        assertEquals(1, requestCount)

        nextBytes = secondBytes
        assertFailsWith<RepositoryReached> { gateway.resync(odinId) }

        assertContentEquals(secondBytes, gateway.avatarBytes(odinId))
        assertEquals(2, requestCount, "resync must drop the cached avatar before the drive sync")
    }

    @Test
    fun syncContactRecordLeavesTheAvatarAlone() = runBlocking {
        val odinId = OdinId("recordonly.me")
        val gateway = ContactInfoGateway(
            contactRepository = { throw RepositoryReached() },
            publicProfiles = provider,
        )

        assertContentEquals(firstBytes, gateway.avatarBytes(odinId))
        nextBytes = secondBytes
        assertFailsWith<RepositoryReached> { gateway.syncContactRecord(odinId) }

        assertContentEquals(firstBytes, gateway.avatarBytes(odinId))
        assertEquals(1, requestCount, "a record-only sync must not re-download the photo")
        assertNull(PublicAvatarRevisions.revisionOf(odinId.domainName))
    }

    private class RepositoryReached : RuntimeException()
}
