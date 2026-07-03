package id.homebase.core.ui.screens.profile

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.image.HomebaseImageData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "ProfileAvatarImageData"

/**
 * Builds the [HomebaseImageData] for a [ProfileAttributeTypes.PHOTO] attribute's image payload,
 * or null if it isn't decodable (missing fileId/keyHeader/payload/IV — e.g. the attribute was
 * hand-constructed rather than read via [id.homebase.api.client.profile.ProfileRepository.loadAttributes]).
 * Mirrors `VaultEntry.imageDataFor`/`ContactBookEntry.profileImageData`: the IV is per-payload,
 * the AES key is the file's.
 *
 * Logs which specific check failed on a null return — this attribute did parse (it has an [id]/
 * [type]/[visibility]), so a null here means the photo silently won't render; the reason is
 * otherwise invisible from the UI (just a placeholder icon).
 */
@OptIn(ExperimentalEncodingApi::class)
fun ProfileAttribute.photoImageData(): HomebaseImageData? {
    val file = fileId ?: return dropped("fileId missing")
    val drive = driveId ?: return dropped("driveId missing")
    val kh = keyHeader ?: return dropped("keyHeader missing")
    val payloadKey = string(ProfileAttributeTypes.KEY_PROFILE_IMAGE)
        ?: return dropped("data.${ProfileAttributeTypes.KEY_PROFILE_IMAGE} missing (data=$data)")
    val descriptor = payloads?.firstOrNull { it.keyEquals(payloadKey) }
        ?: return dropped("no payload descriptor matching key '$payloadKey' (payloads=${payloads?.map { it.key }})")
    val iv = descriptor.iv?.let { runCatching { Base64.decode(it) }.getOrNull() }
        ?: return dropped("payload '$payloadKey' has no/undecodable iv (raw=${descriptor.iv})")

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

private fun ProfileAttribute.dropped(reason: String): HomebaseImageData? {
    Logger.w(tag = TAG) { "photoImageData null for attribute $id ($visibility): $reason" }
    return null
}
