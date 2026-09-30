package id.homebase.core.ui.screens.card

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.client.profile.ProfileVisibility
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
    )
}

class ProfileRepositoryCardStore(private val repository: ProfileRepository) : CardAttributeStore {
    override suspend fun load() = repository.loadAttributes()

    override suspend fun save(data: JsonObject, visibility: ProfileVisibility, id: Uuid?, versionTag: Uuid?, priority: Int) {
        repository.save(
            type = ProfileAttributeTypes.PROFILE_CARD,
            data = data,
            visibility = visibility,
            knownId = id,
            knownVersionTag = versionTag,
            priority = priority,
        )
    }
}

class CardRepository(private val store: CardAttributeStore) {
    // Older servers reject the type outright; the answer won't change until the app restarts.
    private var typeUnsupported = false

    suspend fun cards(): List<ProfileCard> = store.load().profileCards()

    /** A null [overrides] keeps the stored ones. Returns false, without an error, when the server doesn't know the card type. */
    suspend fun savePublic(design: String, overrides: JsonObject? = null): Boolean {
        if (typeUnsupported) return false
        val existing = cards().publicCard()
        val card = existing?.copy(design = design, overrides = overrides ?: existing.overrides)
            ?: ProfileCard(Uuid.NIL, Uuid.NIL, CardAudience.Public, design, overrides ?: JsonObject(emptyMap()))
        try {
            store.save(
                data = card.toData(),
                visibility = ProfileVisibility.ANONYMOUS,
                id = existing?.id,
                versionTag = existing?.versionTag,
                priority = PUBLIC_CARD_PRIORITY,
            )
        } catch (e: ClientException) {
            if (!e.isUnknownCardType()) throw e
            Logger.i(tag = "CardRepository") { "server has no profile_card type; keeping the home page design only" }
            typeUnsupported = true
            return false
        }
        return true
    }
}

private fun ClientException.isUnknownCardType() =
    message?.contains("Unknown profile attribute type", ignoreCase = true) == true
