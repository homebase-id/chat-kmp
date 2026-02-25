package id.homebase.api.client.auth

import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class OwnerSessionRepository(
    private val httpClient: HttpClient
) {

    private val _user = MutableStateFlow<OwnerSession?>(null)
    val user: StateFlow<OwnerSession?> = _user

    suspend fun load(odinId: OdinId) {
        val updated = fetch(odinId)
        _user.value = updated
    }

    private suspend fun fetch(odinId: OdinId): OwnerSession? {
        val url = "https://$odinId/cdn/sitedata.json"
        val raw = httpClient.get(url).bodyAsText()

        val root = Json.parseToJsonElement(raw).jsonArray

        val nameSection = root.find { it.jsonObject["name"]?.jsonPrimitive?.content == "name" }
        val photoSection = root.find { it.jsonObject["name"]?.jsonPrimitive?.content == "photo" }
        val statusSection = root.find { it.jsonObject["name"]?.jsonPrimitive?.content == "status" }

        val nameContent = nameSection
            ?.jsonObject?.get("files")
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("header")
            ?.jsonObject?.get("fileMetadata")
            ?.jsonObject?.get("appData")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.content

        val nameData = nameContent?.let { Json.parseToJsonElement(it) }?.jsonObject?.get("data")?.jsonObject

        val photoFile = photoSection
            ?.jsonObject?.get("files")
            ?.jsonArray?.firstOrNull()
            ?.jsonObject

        val photoHeader = photoFile?.get("header")?.jsonObject

        val photoContent = photoHeader
            ?.get("fileMetadata")
            ?.jsonObject?.get("appData")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.content

        val photoData = photoContent?.let { Json.parseToJsonElement(it) }?.jsonObject?.get("data")?.jsonObject

        val statusContent = statusSection
            ?.jsonObject?.get("files")
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("header")
            ?.jsonObject?.get("fileMetadata")
            ?.jsonObject?.get("appData")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.content

        val statusData = statusContent
            ?.let { Json.parseToJsonElement(it) }
            ?.jsonObject?.get("data")
            ?.jsonObject

        return OwnerSession(
            odinId = odinId,
            displayName = nameData?.get("displayName")?.jsonPrimitive?.contentOrNull,
            firstName = nameData?.get("givenName")?.jsonPrimitive?.contentOrNull,
            surName = nameData?.get("surname")?.jsonPrimitive?.contentOrNull,
            profileImageFileId = photoHeader?.get("fileId")?.jsonPrimitive?.contentOrNull,
            profileImageFileKey = photoData?.get("profileImageKey")?.jsonPrimitive?.contentOrNull,
            profileImagePreviewThumbnail =
                photoHeader?.get("fileMetadata")
                    ?.jsonObject?.get("appData")
                    ?.jsonObject?.get("previewThumbnail")
                    ?.jsonObject?.get("content")
                    ?.jsonPrimitive?.contentOrNull,
            profileImageLastModified =
                photoHeader?.get("fileMetadata")
                    ?.jsonObject?.get("updated")
                    ?.jsonPrimitive?.longOrNull,
            status = statusData?.get("status")?.jsonPrimitive?.contentOrNull
        )
    }

    fun clear() {
        _user.value = null
    }
}
