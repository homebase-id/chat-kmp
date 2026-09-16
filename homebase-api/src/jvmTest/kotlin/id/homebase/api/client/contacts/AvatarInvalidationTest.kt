package id.homebase.api.client.contacts

import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One signal, two publishers: an explicit contact Sync ([ContactInfoGateway.resync]) and the owner's
 * own `publicProfileContentPublished` echo both reach [PublicProfileProviderCached.invalidateImage],
 * which must drop the cached bytes *and* publish a revision — or Coil repaints the copy already in
 * memory (#1526). Every other read stays on the cache.
 */
class AvatarInvalidationTest {

    private val odinId = OdinId("frodobaggins.me")
    private val otherOdinId = OdinId("samwisegamgee.me")

    private val firstBytes = ByteArray(32) { it.toByte() }
    private val secondBytes = ByteArray(16) { 0x7F }

    private var requestCount = 0
    private var nextBytes = firstBytes

    @BeforeTest
    fun resetRevisions() = PublicAvatarRevisions.clear()

    private fun provider(): PublicProfileProviderCached {
        val tempDir = Files.createTempDirectory("hb-avatar-invalidation-test").toString()
        return PublicProfileProviderCached(
            httpClient = HttpClient(
                MockEngine {
                    requestCount++
                    respond(nextBytes, HttpStatusCode.OK)
                }
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            fileOperationsProvider = object : FileOperationsProvider {
                override fun getCacheDirectory() = tempDir
                override fun openFileInput(path: String): InputProvider = error("unused")
                override suspend fun readFileBytes(path: String): ByteArray = error("unused")
                override fun deleteTempFile(path: String) = false
                override fun getFileSize(path: String) = 0L
                override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("unused")
                override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("unused")
                override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("unused")
            },
        )
    }

    @Test
    fun invalidateImageDropsTheCachedAvatarAndRepeatReadsDoNot() = runBlocking {
        val provider = provider()

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
        val provider = provider()

        provider.getPublicImage(odinId)
        assertNull(
            PublicAvatarRevisions.revisionOf(odinId.domainName),
            "a plain read must not bust the avatar cache key",
        )

        provider.invalidateImage(odinId)

        val revision = PublicAvatarRevisions.revisionOf(odinId.domainName)
        assertNotNull(revision, "invalidating an image must publish a revision for that identity")
        assertTrue(revision > 0)
        assertNull(
            PublicAvatarRevisions.revisionOf(otherOdinId.domainName),
            "invalidating one identity must not bust another's avatar",
        )
    }

    @Test
    fun invalidateProfileDoesNotPublishARevision() = runBlocking {
        val provider = provider()

        // A ProfileCard-only republish re-reads the card; the photo is untouched, so every avatar
        // on screen must keep its bytes.
        provider.invalidateProfile(odinId)

        assertNull(PublicAvatarRevisions.revisionOf(odinId.domainName))
    }

    @Test
    fun everyInvalidationPublishesAStrictlyLargerRevision() = runBlocking {
        val provider = provider()

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
    fun clearCachesDropsEveryRevision() = runBlocking {
        val provider = provider()

        provider.invalidateImage(odinId)
        assertNotNull(PublicAvatarRevisions.revisionOf(odinId.domainName))

        // The logout and Storage -> Clear caches path.
        provider.clearCaches()

        assertNull(
            PublicAvatarRevisions.revisionOf(odinId.domainName),
            "logout / clear-caches must not leave one identity's tokens for the next",
        )
    }

    @Test
    fun resyncInvalidatesBeforeItReachesTheContactRepository() = runBlocking {
        val provider = provider()
        var resolved = false
        val gateway = ContactInfoGateway(
            contactRepository = { resolved = true; throw RepositoryReached() },
            publicProfiles = provider,
        )

        assertContentEquals(firstBytes, gateway.avatarBytes(odinId))
        assertEquals(1, requestCount)

        nextBytes = secondBytes
        assertFailsWith<RepositoryReached> { gateway.resync(odinId) }

        assertTrue(resolved, "resync must also re-enrich the local contact record")
        assertNotNull(PublicAvatarRevisions.revisionOf(odinId.domainName))
        assertContentEquals(secondBytes, gateway.avatarBytes(odinId))
        assertEquals(2, requestCount, "resync must drop the cached avatar before the drive sync")
    }

    private class RepositoryReached : RuntimeException()
}
