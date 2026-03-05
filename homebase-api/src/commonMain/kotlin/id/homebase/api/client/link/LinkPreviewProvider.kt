package id.homebase.api.client.link

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.encodeUrl
import io.ktor.client.HttpClient

class LinkPreviewProvider(
    httpClient: HttpClient, credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun getLinkPreview(url: String): LinkPreview? {

        val standardisedUrl = if (url.startsWith("http")) url else "https://${url}"

        val creds = requireCreds()
        val url = apiUrl(
            creds.domain, "/utils/links/extract?url=${encodeUrl(standardisedUrl)}"
        )

        val response = encryptedGet(
            url = url, token = creds.accessToken, secret = creds.secret
        )

        if (response.status == 404) {
            return null
        }

        throwForFailure(response)

        return deserialize<LinkPreview>(response.body)

    }


}