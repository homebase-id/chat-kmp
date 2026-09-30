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
        circleIds: List<String> = emptyList(),
    )
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
    ) {
        repository.save(
            type = ProfileAttributeTypes.PROFILE_CARD,
            data = data,
            visibility = visibility,
            knownId = id,
            knownVersionTag = versionTag,
            priority = priority,
            circleIds = circleIds,
        )
    }
}

class CardRepository(private val store: CardAttributeStore) {
    // Older servers reject the type outright; the answer is per identity, so reset() clears it on logout.
    private var typeUnsupported = false

    // A server without circle cards drops `circleIds` and stores an all-connections card; learned when a write doesn't come back scoped.
    private var circlesUnsupported = false

    val supportsCircleCards: Boolean get() = !typeUnsupported && !circlesUnsupported

    fun reset() {
        typeUnsupported = false
        circlesUnsupported = false
    }

    suspend fun cards(): List<ProfileCard> = store.load().profileCards()

    /** A null [overrides] keeps the stored ones. Returns false, without an error, when the server doesn't know the card type. */
    suspend fun savePublic(design: String, overrides: CardOverrides? = null): Boolean {
        if (typeUnsupported) return false
        val existing = cards().publicCard()
        val card = existing?.let {
            val kept = overrides ?: it.overrides
            it.copy(design = design, overrides = if (it.design != design) kept.prunedFor(design) else kept)
        } ?: ProfileCard(Uuid.NIL, Uuid.NIL, CardAudience.Public, design, overrides ?: CardOverrides.EMPTY)
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

    /**
     * Writes [card] (an existing circle card, edited) back with its circle. Returns false, without
     * an error, when the server doesn't know the card type.
     */
    suspend fun saveCircle(card: ProfileCard): Boolean {
        val circle = card.audience as? CardAudience.Circle ?: error("not a circle card")
        if (!supportsCircleCards) return false
        return writeCircle(card, circle)
    }

    /**
     * Adds a card for [circle], started from [design] and [overrides], at the next free priority.
     * The write is read back: a server that ignores `circleIds` would keep it as a card for every
     * connection, so a card that doesn't return scoped to [circle] is deleted and the server is
     * marked as not supporting circle cards.
     */
    suspend fun addCircle(circle: CardAudience.Circle, design: String, overrides: CardOverrides): AddCircleCardResult {
        if (!supportsCircleCards) return AddCircleCardResult.Unsupported
        val stored = cards()
        val priority = (stored.filter { it.audience is CardAudience.Circle }.maxOfOrNull { it.priority } ?: -1) + 1
        val card = ProfileCard(Uuid.NIL, Uuid.NIL, circle, design, overrides.prunedFor(design), priority)
        if (!writeCircle(card, circle)) return AddCircleCardResult.Unsupported
        val written = store.load()
        val scoped = written.profileCards().firstOrNull { (it.audience as? CardAudience.Circle)?.id?.sameCircleId(circle.id) == true }
        if (scoped != null) return AddCircleCardResult.Added(scoped)
        circlesUnsupported = true
        Logger.i(tag = "CardRepository") { "server ignored circleIds; removing the unscoped card" }
        written.filter { it.type == ProfileAttributeTypes.PROFILE_CARD && it.visibility == ProfileVisibility.CONNECTED && it.acl.circleIdList.isNullOrEmpty() }
            .forEach { store.delete(it.id, it.versionTag) }
        return AddCircleCardResult.Unsupported
    }

    /** Deletes a circle card; the public card is never deleted. */
    suspend fun delete(card: ProfileCard): Boolean {
        require(card.audience is CardAudience.Circle) { "the public card cannot be deleted" }
        return store.delete(card.id, card.versionTag)
    }

    private suspend fun writeCircle(card: ProfileCard, circle: CardAudience.Circle): Boolean {
        try {
            store.save(
                data = card.toData(),
                visibility = ProfileVisibility.CONNECTED,
                id = card.id.takeIf { it != Uuid.NIL },
                versionTag = card.versionTag.takeIf { it != Uuid.NIL },
                priority = card.priority,
                circleIds = listOf(circle.id),
            )
        } catch (e: ClientException) {
            if (!e.isUnknownCardType()) throw e
            typeUnsupported = true
            return false
        }
        return true
    }
}

sealed interface AddCircleCardResult {
    data class Added(val card: ProfileCard) : AddCircleCardResult
    data object Unsupported : AddCircleCardResult
}

internal fun String.sameCircleId(other: String) = replace("-", "").equals(other.replace("-", ""), ignoreCase = true)

private fun ClientException.isUnknownCardType() =
    message.orEmpty().let {
        it.contains("Unknown profile attribute type", ignoreCase = true) &&
            it.contains(ProfileAttributeTypes.PROFILE_CARD, ignoreCase = true)
    }
