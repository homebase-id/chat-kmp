package id.homebase.core.ui.screens.card

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.client.profile.ProfileWriteResponse
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject

interface CardAttributeStore {
    suspend fun load(): List<ProfileAttribute>
    suspend fun save(
        data: JsonObject,
        visibility: ProfileVisibility,
        id: Uuid?,
        versionTag: Uuid?,
        priority: Int,
        circleIds: List<String> = emptyList(),
    ): ProfileWriteResponse
    suspend fun delete(id: Uuid, versionTag: Uuid): Boolean
}

class ProfileRepositoryCardStore(private val repository: ProfileRepository) : CardAttributeStore {
    override suspend fun load() = repository.loadAttributes()

    override suspend fun delete(id: Uuid, versionTag: Uuid) = repository.delete(id, versionTag)

    override suspend fun save(
        data: JsonObject,
        visibility: ProfileVisibility,
        id: Uuid?,
        versionTag: Uuid?,
        priority: Int,
        circleIds: List<String>,
    ) = repository.save(
        type = ProfileAttributeTypes.PROFILE_CARD,
        data = data,
        visibility = visibility,
        knownId = id,
        knownVersionTag = versionTag,
        priority = priority,
        circleIds = circleIds,
    )
}

class CardRepository(private val store: CardAttributeStore) {
    // Older servers reject the type outright; the answer is per identity, so reset() clears it on logout.
    private var typeUnsupported = false

    val supportsCircleCards: Boolean get() = !typeUnsupported

    fun reset() {
        typeUnsupported = false
    }

    suspend fun cards(): List<ProfileCard> = store.load().profileCards()

    /** A null [overrides] keeps the stored ones. Returns false, without an error, when the server doesn't know the card type. */
    suspend fun savePublic(design: String, overrides: CardOverrides? = null): Boolean {
        if (typeUnsupported) return false
        val existing = cards().publicCard()
        val card = existing?.withDesign(design, overrides) ?: ProfileCard(Uuid.NIL, Uuid.NIL, CardAudience.Public, design, overrides ?: CardOverrides.EMPTY)
        return saveOrUnsupported(
            data = card.toData(),
            visibility = ProfileVisibility.ANONYMOUS,
            id = existing?.id,
            versionTag = existing?.versionTag,
            priority = PUBLIC_CARD_PRIORITY,
        ) != null
    }

    suspend fun saveCircle(card: ProfileCard): ProfileCard? {
        val circle = card.audience as? CardAudience.Circle ?: error("not a circle card")
        if (!supportsCircleCards) return null
        val written = saveOrUnsupported(
            data = card.toData(),
            visibility = ProfileVisibility.CONNECTED,
            id = card.id.takeIf { it != Uuid.NIL },
            versionTag = card.versionTag.takeIf { it != Uuid.NIL },
            priority = card.priority,
            circleIds = listOf(circle.id),
        ) ?: return null
        return card.copy(id = written.id, versionTag = written.versionTag)
    }

    // 404 counts as reset.
    suspend fun resetPublic() {
        cards().publicCard()?.let { store.delete(it.id, it.versionTag) }
    }

    suspend fun resetCircle(card: ProfileCard) {
        require(card.audience is CardAudience.Circle) { "not a circle card" }
        if (!card.isDefault) store.delete(card.id, card.versionTag)
    }

    private suspend fun saveOrUnsupported(
        data: JsonObject,
        visibility: ProfileVisibility,
        id: Uuid?,
        versionTag: Uuid?,
        priority: Int,
        circleIds: List<String> = emptyList(),
    ): ProfileWriteResponse? =
        try {
            store.save(data, visibility, id, versionTag, priority, circleIds)
        } catch (e: ClientException) {
            if (!e.isUnknownCardType()) throw e
            Logger.i(tag = "CardRepository") { "server has no profile_card type; keeping the home page design only" }
            typeUnsupported = true
            null
        }
}

private fun ClientException.hasMessage(fragment: String) = message.orEmpty().contains(fragment, ignoreCase = true)

private fun ClientException.isUnknownCardType() =
    hasMessage("Unknown profile attribute type") && hasMessage(ProfileAttributeTypes.PROFILE_CARD)
