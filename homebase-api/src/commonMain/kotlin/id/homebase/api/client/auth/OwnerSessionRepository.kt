package id.homebase.api.client.auth

import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.identity.siteDataSectionData
import id.homebase.api.client.identity.siteDataSectionHeader
import id.homebase.api.common.OdinId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class OwnerSessionRepository(
    private val publicIdentityRepository: PublicIdentityRepository,
) {

    private val _user = MutableStateFlow<OwnerSession?>(null)
    val user: StateFlow<OwnerSession?> = _user

    suspend fun load(odinId: OdinId) {
        // Emit a minimal fallback immediately so the UI can render without waiting for HTTP.
        _user.value = fallback(odinId)
        _user.value = fetch(odinId)
    }

    private suspend fun fetch(odinId: OdinId): OwnerSession {
        val root = publicIdentityRepository.fetchSiteData(odinId) ?: return fallback(odinId)

        val nameData = root.siteDataSectionData("name")
        val statusData = root.siteDataSectionData("status")
        val photoHeader = root.siteDataSectionHeader("photo")
        val photoData = root.siteDataSectionData("photo")

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
            status = statusData?.get("status")?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun fallback(odinId: OdinId): OwnerSession = OwnerSession(
        odinId = odinId,
        displayName = odinId.toString(),
        firstName = null,
        surName = null,
        profileImageFileId = null,
        profileImageFileKey = null,
        profileImagePreviewThumbnail = null,
        profileImageLastModified = null,
        status = null,
    )

    fun clear() {
        _user.value = null
    }
}
