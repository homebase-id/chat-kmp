package id.homebase.api.client.profile

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient

class PublicProfileProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
    fileOperationsProvider: FileOperationsProvider
) : OdinApiProviderBase(httpClient, credentialsManager) {

    private val cached =
        PublicProfileProviderCached(
            httpClient = httpClient,
            fileOperationsProvider = fileOperationsProvider
        )

    suspend fun getPublicProfile(
        odinId: OdinId
    ): ProfileCard {
        return cached.getPublicProfile(odinId)
            ?: throw Exception("Profile not found")
    }

    suspend fun getPublicImage(
        odinId: OdinId
    ): ByteArray {
        return cached.getPublicImage(odinId)
            ?: throw Exception("Image not found")
    }

    suspend fun clearCache() {
        cached.clearCaches()
    }
}