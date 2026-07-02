package id.homebase.core.ui.screens.profile

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.image.HomebaseImageData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Builds the [HomebaseImageData] for a [ProfileAttributeTypes.PHOTO] attribute's image payload,
 * or null if it isn't decodable (missing fileId/keyHeader/payload/IV — e.g. the attribute was
 * hand-constructed rather than read via [id.homebase.api.client.profile.ProfileRepository.loadAttributes]).
 * Mirrors `VaultEntry.imageDataFor`/`ContactBookEntry.profileImageData`: the IV is per-payload,
 * the AES key is the file's.
 */
@OptIn(ExperimentalEncodingApi::class)
fun ProfileAttribute.photoImageData(): HomebaseImageData? {
    val file = fileId ?: return null
    val drive = driveId ?: return null
    val kh = keyHeader ?: return null
    val payloadKey = string(ProfileAttributeTypes.KEY_PROFILE_IMAGE) ?: return null
    val descriptor = payloads?.firstOrNull { it.keyEquals(payloadKey) } ?: return null
    val iv = descriptor.iv?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null

    return HomebaseImageData(
        driveId = drive,
        fileId = file,
        payloadKey = descriptor.key,
        loadFullPayload = false,
        lastModified = descriptor.lastModified,
        payloadContentType = descriptor.contentType,
        keyHeader = KeyHeader(iv = iv, aesKey = kh.aesKey),
    )
}
