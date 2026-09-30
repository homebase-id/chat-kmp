package id.homebase.core.ui.screens.card

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.client.profile.ProfileWriteResponse
import kotlin.coroutines.cancellation.CancellationException
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

class CardRepository(private val store: CardAttributeStore, private val preferences: CardPreferences) {
    // Older servers reject the type outright; the answer is per identity, so reset() clears it on logout.
    private var typeUnsupported = false

    val supportsCircleCards: Boolean get() = !typeUnsupported && !preferences.circleCardsUnsupported

    fun reset() {
        typeUnsupported = false
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
        return saveOrUnsupported(
            data = card.toData(),
            visibility = ProfileVisibility.ANONYMOUS,
            id = existing?.id,
            versionTag = existing?.versionTag,
            priority = PUBLIC_CARD_PRIORITY,
        ) != null
    }

    suspend fun saveCircle(card: ProfileCard): Boolean {
        val circle = card.audience as? CardAudience.Circle ?: error("not a circle card")
        if (!supportsCircleCards) return false
        return writeCircle(card, circle, ProfileVisibility.CONNECTED) != null
    }

    // A server without circle cards drops circleIds, so a Connected write there would reach every connection:
    // write owner-only first and promote only once the circle is known to stick.
    suspend fun addCircle(circle: CardAudience.Circle, design: String, overrides: CardOverrides): AddCircleCardResult {
        if (!supportsCircleCards) return AddCircleCardResult.Unsupported
        val stored = cards()
        val priority = (stored.filter { it.audience is CardAudience.Circle }.maxOfOrNull { it.priority } ?: -1) + 1
        val card = ProfileCard(Uuid.NIL, Uuid.NIL, circle, design, overrides.prunedFor(design), priority)
        val draft = try {
            writeCircle(card, circle, ProfileVisibility.OWNER) ?: return AddCircleCardResult.Unsupported
        } catch (e: ClientException) {
            // A server that scopes cards refuses circles on anything but Connected, which proves it scopes them.
            if (!e.isCircleIdsNeedConnected()) throw e
            null
        }
        val promoted = if (draft == null) card else {
            val readBack = store.load().firstOrNull { it.id == draft.id } ?: error("the new circle card ${draft.id} did not read back")
            if (readBack.acl.circleIdList?.singleOrNull()?.sameCircleId(circle.id) != true) {
                Logger.i(tag = "CardRepository") { "server ignored circleIds; circle cards are unsupported" }
                preferences.setCircleCardsUnsupported()
                deleted(readBack)
                return AddCircleCardResult.Unsupported
            }
            card.copy(id = readBack.id, versionTag = readBack.versionTag)
        }
        val written = writeCircle(promoted, circle, ProfileVisibility.CONNECTED) ?: return AddCircleCardResult.Unsupported
        return AddCircleCardResult.Added(promoted.copy(id = written.id, versionTag = written.versionTag))
    }

    suspend fun delete(card: ProfileCard): Boolean {
        require(card.audience is CardAudience.Circle) { "the public card cannot be deleted" }
        return store.delete(card.id, card.versionTag)
    }

    // A false from the store means the attribute is already gone (404); only a throw is a failed delete.
    private suspend fun deleted(attribute: ProfileAttribute): Boolean =
        try {
            store.delete(attribute.id, attribute.versionTag)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = "CardRepository", throwable = e) { "deleting the owner-only circle card ${attribute.id} failed" }
            false
        }

    private suspend fun writeCircle(card: ProfileCard, circle: CardAudience.Circle, visibility: ProfileVisibility) =
        saveOrUnsupported(
            data = card.toData(),
            visibility = visibility,
            id = card.id.takeIf { it != Uuid.NIL },
            versionTag = card.versionTag.takeIf { it != Uuid.NIL },
            priority = card.priority,
            circleIds = listOf(circle.id),
        )

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

private fun ClientException.isCircleIdsNeedConnected() =
    message.orEmpty().contains("CircleIds can only be set when visibility is Connected", ignoreCase = true)
