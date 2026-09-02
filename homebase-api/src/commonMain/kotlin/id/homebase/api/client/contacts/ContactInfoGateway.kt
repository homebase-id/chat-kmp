@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.client.cache.CacheStats
import id.homebase.api.client.profile.ProfileCard
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import kotlin.uuid.ExperimentalUuidApi

// The one place that reaches /pub/profile and /pub/image. A known contact's name resolves from
// the synced Contacts drive; the public endpoints serve everything else.
class ContactInfoGateway internal constructor(
    private val contactRepository: ContactRepository,
    private val publicProfiles: PublicProfileProviderCached,
) {

    suspend fun displayName(odinId: OdinId): String? {
        localContact(odinId)?.content?.name.resolveDisplayName()?.let { return it }
        return runCatching { publicProfiles.getPublicProfile(odinId)?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    // Not the contact's own prfl_pic payload: that read 500s for some contacts. The public read
    // is client-TTL'd and stale-while-revalidate, so it stays local after the first fetch.
    suspend fun avatarBytes(odinId: OdinId): ByteArray? = publicProfiles.getPublicImage(odinId)

    // Always the public read: a synced contact stores name/avatar, not the card.
    suspend fun profileCard(odinId: OdinId): ProfileCard? = publicProfiles.getPublicProfile(odinId)

    suspend fun getCacheStats(): List<CacheStats> = publicProfiles.getCacheStats()

    suspend fun clearCaches() = publicProfiles.clearCaches()

    private suspend fun localContact(odinId: OdinId): Contact? {
        contactRepository.ensureLoaded()
        val domain = odinId.domainName
        return contactRepository.contacts.value.firstOrNull {
            it.content.odinId?.equals(domain, ignoreCase = true) == true
        }
    }
}
