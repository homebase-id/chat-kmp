package id.homebase.api.client.auth

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class OwnerSessionRepository(
    private val httpClient: HttpClient,
    private val settings: Settings
) {

    companion object {
        private const val KEY_ODIN_ID = "owner_session_odin_id"
        private const val KEY_DISPLAY_NAME = "owner_session_display_name"
        private const val KEY_FIRST_NAME = "owner_session_first_name"
        private const val KEY_SUR_NAME = "owner_session_sur_name"
        private const val KEY_PROFILE_IMAGE_FILE_ID = "owner_session_profile_image_file_id"
        private const val KEY_PROFILE_IMAGE_FILE_KEY = "owner_session_profile_image_file_key"
        private const val KEY_PROFILE_IMAGE_THUMBNAIL = "owner_session_profile_image_thumbnail"
        private const val KEY_PROFILE_IMAGE_LAST_MODIFIED = "owner_session_profile_image_last_modified"
        private const val KEY_STATUS = "owner_session_status"
    }

    private val _user = MutableStateFlow<OwnerSession?>(null)
    val user: StateFlow<OwnerSession?> = _user

    fun loadCached(odinId: OdinId) {
        val cachedOdinId = settings.getStringOrNull(KEY_ODIN_ID) ?: return
        if (cachedOdinId != odinId.domainName) return
        _user.value = OwnerSession(
            odinId = odinId,
            displayName = settings.getStringOrNull(KEY_DISPLAY_NAME),
            firstName = settings.getStringOrNull(KEY_FIRST_NAME),
            surName = settings.getStringOrNull(KEY_SUR_NAME),
            profileImageFileId = settings.getStringOrNull(KEY_PROFILE_IMAGE_FILE_ID),
            profileImageFileKey = settings.getStringOrNull(KEY_PROFILE_IMAGE_FILE_KEY),
            profileImagePreviewThumbnail = settings.getStringOrNull(KEY_PROFILE_IMAGE_THUMBNAIL),
            profileImageLastModified = settings.getLongOrNull(KEY_PROFILE_IMAGE_LAST_MODIFIED),
            status = settings.getStringOrNull(KEY_STATUS)
        )
    }

    suspend fun load(odinId: OdinId) {
        val updated = fetch(odinId)
        saveToCache(updated)
        _user.value = updated
    }

    private fun saveToCache(session: OwnerSession) {
        settings.putString(KEY_ODIN_ID, session.odinId.domainName)
        session.displayName?.let { settings.putString(KEY_DISPLAY_NAME, it) }
            ?: settings.remove(KEY_DISPLAY_NAME)
        session.firstName?.let { settings.putString(KEY_FIRST_NAME, it) }
            ?: settings.remove(KEY_FIRST_NAME)
        session.surName?.let { settings.putString(KEY_SUR_NAME, it) }
            ?: settings.remove(KEY_SUR_NAME)
        session.profileImageFileId?.let { settings.putString(KEY_PROFILE_IMAGE_FILE_ID, it) }
            ?: settings.remove(KEY_PROFILE_IMAGE_FILE_ID)
        session.profileImageFileKey?.let { settings.putString(KEY_PROFILE_IMAGE_FILE_KEY, it) }
            ?: settings.remove(KEY_PROFILE_IMAGE_FILE_KEY)
        session.profileImagePreviewThumbnail?.let { settings.putString(KEY_PROFILE_IMAGE_THUMBNAIL, it) }
            ?: settings.remove(KEY_PROFILE_IMAGE_THUMBNAIL)
        session.profileImageLastModified?.let { settings.putLong(KEY_PROFILE_IMAGE_LAST_MODIFIED, it) }
            ?: settings.remove(KEY_PROFILE_IMAGE_LAST_MODIFIED)
        session.status?.let { settings.putString(KEY_STATUS, it) }
            ?: settings.remove(KEY_STATUS)
    }

    private suspend fun fetch(odinId: OdinId): OwnerSession {
        val url = "https://$odinId/cdn/sitedata.json"


        val response = try {
            httpClient.get(url)
        } catch (e: Exception) {
            Logger.e(tag = "OwnerSessionRepository") { "Fetching $url failed: ${e.message}" }
            null
        }

        if (response == null || response.status == HttpStatusCode.NotFound) {
            return OwnerSession(
                odinId = odinId,
                displayName = odinId.toString(),
                firstName = null,
                surName = null,
                profileImageFileId = null,
                profileImageFileKey = null,
                profileImagePreviewThumbnail = null,
                profileImageLastModified = null,
                status = null
            )
        }

        val root = Json.parseToJsonElement(response.bodyAsText()).jsonArray

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
        settings.remove(KEY_ODIN_ID)
    }
}
