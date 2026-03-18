package id.homebase.api.client.profile

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient

class PublicProfileProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
    private val cached: PublicProfileProviderCached
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun getPublicProfile(
        odinId: OdinId
    ): ProfileCard {
        return cached.getPublicProfile(odinId)
            ?: throw Exception("Profile not found")
    }

    suspend fun getPublicImage(odinId: OdinId): ByteArray? =
        cached.getPublicImage(odinId)

    suspend fun clearCache() {
        cached.clearCaches()
    }
}
