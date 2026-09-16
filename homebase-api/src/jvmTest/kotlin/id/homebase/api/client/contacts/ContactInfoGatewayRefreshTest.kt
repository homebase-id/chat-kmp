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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A user-initiated Sync is the one read allowed to defeat the client TTL (#1526): it must drop the
 * cached avatar bytes *and* publish a new revision, or Coil repaints the copy already in memory.
 * Every other read stays on the cache.
 */
class ContactInfoGatewayRefreshTest {

    private val odinId = OdinId("frodobaggins.me")
    private val otherOdinId = OdinId("samwisegamgee.me")

    private val firstBytes = ByteArray(32) { it.toByte() }
    private val secondBytes = ByteArray(16) { 0x7F }

    private var requestCount = 0
    private var nextBytes = firstBytes

    @BeforeTest
    fun resetRevisions() = PublicAvatarRevisions.clear()

    private fun gateway(): ContactInfoGateway {
        val tempDir = Files.createTempDirectory("hb-gateway-refresh-test").toString()
        val provider = PublicProfileProviderCached(
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
        return ContactInfoGateway(
            contactRepository = { error("not used by the avatar path") },
            publicProfiles = provider,
        )
    }

    @Test
    fun refreshDropsTheCachedAvatarAndRepeatReadsDoNot() = runBlocking {
        val gateway = gateway()

        assertContentEquals(firstBytes, gateway.avatarBytes(odinId))
        assertEquals(1, requestCount)

        // Normal caching: an unrelated read seconds later must not hit the network again.
        assertContentEquals(firstBytes, gateway.avatarBytes(odinId))
        assertEquals(1, requestCount, "a plain read must stay on the cache")

        nextBytes = secondBytes
        gateway.refresh(odinId)

        assertContentEquals(secondBytes, gateway.avatarBytes(odinId))
        assertEquals(2, requestCount, "refresh must force the next read to the host")

        // ...and the refreshed bytes are cached again, not re-fetched on every read.
        assertContentEquals(secondBytes, gateway.avatarBytes(odinId))
        assertEquals(2, requestCount, "caching must resume after a forced refresh")
    }

    @Test
    fun refreshPublishesARevisionForThatIdentityOnly() = runBlocking {
        val gateway = gateway()

        assertNull(PublicAvatarRevisions.revisionOf(odinId.domainName))

        gateway.avatarBytes(odinId)
        assertNull(
            PublicAvatarRevisions.revisionOf(odinId.domainName),
            "a plain read must not bust the avatar URL",
        )

        gateway.refresh(odinId)

        val revision = PublicAvatarRevisions.revisionOf(odinId.domainName)
        assertNotNull(revision, "refresh must publish a revision for the refreshed identity")
        assertTrue(revision > 0)
        assertNull(
            PublicAvatarRevisions.revisionOf(otherOdinId.domainName),
            "refreshing one identity must not bust another's avatar URL",
        )
    }

    @Test
    fun everyRefreshPublishesAStrictlyLargerRevision() = runBlocking {
        val gateway = gateway()

        gateway.refresh(odinId)
        val first = assertNotNull(PublicAvatarRevisions.revisionOf(odinId.domainName))
        gateway.refresh(odinId)
        val second = assertNotNull(PublicAvatarRevisions.revisionOf(odinId.domainName))

        // A device clock stepping backwards must not revert the URL to one Coil already has bytes
        // for — that would silently disarm the refresh.
        assertTrue(
            second > first,
            "a second refresh must publish a strictly larger revision ($second was not > $first)",
        )
    }

    @Test
    fun clearCachesDropsEveryRevision() = runBlocking {
        val gateway = gateway()

        gateway.refresh(odinId)
        assertNotNull(PublicAvatarRevisions.revisionOf(odinId.domainName))

        // The logout and Storage -> Clear caches path.
        gateway.clearCaches()

        assertNull(
            PublicAvatarRevisions.revisionOf(odinId.domainName),
            "logout / clear-caches must not leave one identity's tokens for the next",
        )
    }
}
