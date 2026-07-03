package id.homebase.core.ui.screens.profile

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.image.HomebaseImageData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "ProfileAvatarImageData"

/**
 * Builds the [HomebaseImageData] for a [ProfileAttributeTypes.PHOTO] attribute's image payload,
 * or null if it isn't decodable (missing fileId/keyHeader/payload, or a missing/corrupt IV when
 * one was expected — e.g. the attribute was hand-constructed rather than read via
 * [id.homebase.api.client.profile.ProfileRepository.loadAttributes]).
 *
 * Whether a real IV is required is decided from [visibility], not from whether `descriptor.iv`
 * happens to be present — per [SetPhotoAttributeRequest]'s doc (confirmed with the server team),
 * the server encrypts the payload at rest for CONNECTED/OWNER and stores it unencrypted ("as-is")
 * for ANONYMOUS/AUTHENTICATED, so an Anonymous/Authenticated payload legitimately never gets one.
 * Deciding from `iv == null` alone would be a coincidental heuristic, not a real signal: a
 * Connected/Owner payload with a missing IV due to some *other* bug would be silently treated as
 * "unencrypted" and fetched with a placeholder IV — if the server actually did encrypt those bytes,
 * they'd "decrypt" with the wrong IV and render as corrupted garbage instead of failing loudly.
 * (`fileMetadata.isEncrypted` isn't a usable signal here either: [id.homebase.api.client.drives.ServerFile]
 * unconditionally resets it to `false` after processing every file, success or soft-fail, so it's
 * always `false` by the time anything downstream could read it.)
 *
 * For the tiers that never get an IV, we still fetch through the same authenticated drive-payload
 * endpoint with a placeholder zero IV — Anonymous doesn't mean unauthenticated at the HTTP layer,
 * only unencrypted at rest — and
 * [DriveFileHttpProvider.decryptBytes][id.homebase.api.client.drives.files.DriveFileHttpProvider.decryptBytes]
 * skips decryption based on the server's own `payloadencrypted` response header for that fetch,
 * ignoring whatever [KeyHeader] we pass when that header is false, so the placeholder is never used.
 *
 * Mirrors `VaultEntry.imageDataFor`/`ContactBookEntry.profileImageData`, except for that one
 * divergence (they both always require a real IV — every Vault/contact-sync payload is encrypted,
 * so a null IV there means "still uploading", not "unencrypted"; see `VaultEntry`/`VaultStream`'s
 * `isPending` checks).
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

    val expectsEncryptedPayload = visibility == ProfileVisibility.CONNECTED || visibility == ProfileVisibility.OWNER
    val iv = if (!expectsEncryptedPayload) {
        ByteArray(16) // unused — DriveFileHttpProvider skips decrypt via the response header, see above
    } else {
        val rawIv = descriptor.iv
            ?: return dropped("payload '$payloadKey' expected encrypted ($visibility) but has no iv")
        runCatching { Base64.decode(rawIv) }.getOrElse {
            return dropped("payload '$payloadKey' iv present but undecodable (raw=$rawIv)")
        }
    }

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
