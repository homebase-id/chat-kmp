package id.homebase.api.client.contacts

import id.homebase.api.client.profile.FakeFileOperationsProvider
import id.homebase.api.client.profile.PublicProfileProviderCached
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
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
            fileOperationsProvider = FakeFileOperationsProvider(tempDir),
        )
    }

    @Test
    fun constructionDoesNotResolveContactRepository() {
        var resolved = false
        ContactInfoGateway(
            contactRepository = { resolved = true; error("resolved at construction") },
            publicProfiles = provider(),
            driveFiles = unusedDriveFiles(),
            contactHeaders = { _, _ -> null },
        )
        assertFalse(resolved, "constructing the gateway must not resolve ContactRepository")
    }
}
