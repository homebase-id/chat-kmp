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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

/**
 * The Coil ImageLoader builds a PublicImageFetcher.Factory holding this gateway before
 * DatabaseManager.initialize() has run. ContactRepository needs the database, so resolving it
 * from the gateway's constructor kills the launch with a Koin InstanceCreationException wrapping
 * UninitializedPropertyAccessException.
 */
class ContactInfoGatewayLazyRepositoryTest {

    private val imageBytes = ByteArray(32) { it.toByte() }

    private fun provider(): PublicProfileProviderCached {
        val tempDir = Files.createTempDirectory("hb-gateway-lazy-test").toString()
        return PublicProfileProviderCached(
            httpClient = HttpClient(MockEngine { respond(imageBytes, HttpStatusCode.OK) }),
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
    fun constructionDoesNotResolveContactRepository() {
        var resolved = false
        ContactInfoGateway(
            contactRepository = { resolved = true; error("resolved at construction") },
            publicProfiles = provider(),
        )
        assertFalse(resolved, "constructing the gateway must not resolve ContactRepository")
    }

    @Test
    fun avatarBytesDoesNotResolveContactRepository() = runBlocking {
        var resolved = false
        val gateway = ContactInfoGateway(
            contactRepository = { resolved = true; error("resolved during avatar read") },
            publicProfiles = provider(),
        )
        assertContentEquals(imageBytes, gateway.avatarBytes(OdinId("frodobaggins.me")))
        assertFalse(resolved, "the avatar read must not resolve ContactRepository")
    }
}
