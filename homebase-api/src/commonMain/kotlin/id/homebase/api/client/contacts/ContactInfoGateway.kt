@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.cache.CacheStats
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.profile.ProfileCard
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi

private const val TAG = "ContactInfoGateway"

// The one place that reaches /pub/profile and /pub/image. A known contact's name resolves from
// the synced Contacts drive; the public endpoints serve everything else.
class ContactInfoGateway internal constructor(
    // Resolved on demand: the Coil ImageLoader builds a PublicImageFetcher.Factory holding this
    // gateway before DatabaseManager.initialize() runs, and ContactRepository needs the database.
    private val contactRepository: () -> ContactRepository,
    private val publicProfiles: PublicProfileProviderCached,
    private val driveFiles: DriveFileProviderCached,
    private val contactHeaders: ContactHeaderReader,
) {

    suspend fun displayName(odinId: OdinId): String? {
        localContact(odinId)?.content?.name.resolveDisplayName()?.let { return it }
        return runCatching { publicProfiles.getPublicProfile(odinId)?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun avatarBytes(odinId: OdinId): ByteArray? {
        val repository = contactRepository()
        repository.ensureLoaded()
        val contacts = repository.contacts.value
        val stored = resolveAvatar(odinId, contacts, AvatarCapability.Any) as? AvatarSource.StoredPhoto
        stored?.let { source ->
            val contact = contacts.first { it.image == source.image }
            localAvatarBytes(contact, source.image)?.let { return it }
        }
        return publicProfiles.getPublicImage(odinId)
    }

    // Always the public read: a synced contact stores name/avatar, not the card.
    suspend fun profileCard(odinId: OdinId): ProfileCard? = publicProfiles.getPublicProfile(odinId)

    // A user-initiated Sync: defeat the client TTL on both public artifacts (dropping the image
    // publishes the avatar revision) and re-enrich the local contact record from the peer.
    suspend fun resync(odinId: OdinId) {
        publicProfiles.invalidateProfile(odinId)
        publicProfiles.invalidateImage(odinId)
        contactRepository().sync(odinId)
    }

    // Same, minus the avatar: for paths where nothing suggests the photo changed, so the next paint
    // is not a guaranteed unauthenticated re-download of bytes already held.
    suspend fun syncContactRecord(odinId: OdinId) {
        publicProfiles.invalidateProfile(odinId)
        contactRepository().sync(odinId)
    }

    suspend fun getCacheStats(): List<CacheStats> = publicProfiles.getCacheStats()

    suspend fun clearCaches() = publicProfiles.clearCaches()

    // A payload replaced under us answers 404, and its IV moves with it: the re-read header names
    // the current version, which the version-addressed cache key then fetches fresh.
    private suspend fun localAvatarBytes(contact: Contact, ref: ContactImageRef): ByteArray? {
        readAvatarOrWarn(ref) { "local avatar read failed for ${ref.fileId}; re-reading its header" }
            ?.let { return it }
        val current = contactHeaders.getHeaderByUid(ref.driveId, contact.uniqueId)?.toContact()?.image
            ?: return null
        return readAvatarOrWarn(current) { "local avatar retry failed for ${ref.fileId}" }
    }

    private suspend fun readAvatarOrWarn(ref: ContactImageRef, warning: () -> String): ByteArray? =
        try {
            readAvatar(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { warning() }
            null
        }

    // The response's payloadencrypted header decides, not ref.isEncrypted: decrypting the header
    // content clears fileMetadata.isEncrypted, so a synced contact always reads as unencrypted.
    private suspend fun readAvatar(ref: ContactImageRef): ByteArray {
        return checkNotNull(driveFiles.getPayloadBytesDecrypted(
            driveId = ref.driveId,
            fileId = ref.fileId,
            key = ref.payload.key,
            keyHeader = ref.payloadKeyHeader() ?: KeyHeader.empty(),
            lastModified = ref.payload.lastModified,
        )).bytes
    }

    private suspend fun localContact(odinId: OdinId): Contact? {
        val repository = contactRepository()
        repository.ensureLoaded()
        return repository.contacts.value.matching(odinId).firstOrNull()
    }
}
