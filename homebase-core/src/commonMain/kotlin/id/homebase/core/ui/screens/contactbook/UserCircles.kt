package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.CreateAppCircleRequest
import id.homebase.api.client.connections.DriveGrantRequest
import id.homebase.api.client.connections.PermissionedDrive
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.api.util.compareStringUuId
import id.homebase.api.util.truncateToCodePoints
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DrivePermissionSet
import id.homebase.core.config.AppConfig
import id.homebase.core.config.profileLabeledDrive
import id.homebase.core.ui.screens.card.CardAudience
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.ui.screens.card.ownPhotoFor
import id.homebase.core.ui.screens.card.profileCards
import id.homebase.core.ui.screens.card.contactsCircles
import id.homebase.core.ui.screens.card.isSameAs
import id.homebase.core.ui.screens.card.shownOn
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.audience
import id.homebase.core.ui.screens.profile.saveWithAudience
import kotlin.uuid.Uuid

internal const val USER_CIRCLE_SLUG_PREFIX = "c-"
internal const val CIRCLE_NAME_MAX = 40
internal const val CIRCLE_DESCRIPTION_MAX = 140
internal const val CIRCLE_EMOJI_MAX_CHARS = 16

private val chatAppUuid = Uuid.parseHex(AppConfig.APP_ID)

/** `c-` plus 12 hex characters: within the server's 14-character slug limit. */
fun newCircleSlug(): String = USER_CIRCLE_SLUG_PREFIX + Uuid.random().toHexString().take(12)

/** Made in this app, which is what the `c-` prefix on the slug says; the only circles it may delete. */
fun RedactedCircleDefinition.isUserMadeCircle(): Boolean =
    grantOn == CircleGrantOn.None && appId == chatAppUuid && slug?.startsWith(USER_CIRCLE_SLUG_PREFIX) == true

data class CircleDraft(
    val slug: String,
    val name: String,
    val emoji: String = "",
    val description: String = "",
) {
    fun toRequest() = CreateAppCircleRequest(
        name = name.trim().truncateToCodePoints(CIRCLE_NAME_MAX),
        slug = slug,
        description = description.trim().takeIf { it.isNotEmpty() }?.truncateToCodePoints(CIRCLE_DESCRIPTION_MAX),
        emoji = emoji.trim().takeIf { it.isNotEmpty() },
        driveGrants = listOf(
            DriveGrantRequest(
                PermissionedDrive(profileLabeledDrive.drive, DrivePermissionSet(listOf(DrivePermission.Read))),
            ),
        ),
    )
}

interface UserCircleBackend {
    suspend fun createCircle(request: CreateAppCircleRequest): Uuid
    suspend fun deleteCircle(circleId: Uuid, removeMembers: Boolean)
    suspend fun circles(): List<CardCircle>
    suspend fun attributes(): List<ProfileAttribute>
    suspend fun saveAudience(attribute: ProfileAttribute, audience: ProfileAudience): ProfileAttribute
    suspend fun deleteAttribute(id: Uuid, versionTag: Uuid): Boolean
}

class UserCircleService(private val backend: UserCircleBackend) {
    suspend fun create(draft: CircleDraft): Uuid = backend.createCircle(draft.toRequest())

    /**
     * Takes the circle off the profile first, so a failure part way leaves a circle that still
     * exists rather than attributes naming one that does not; the server refuses writes that name a
     * deleted circle.
     */
    suspend fun delete(circleId: String, removeMembers: Boolean) {
        val target = CardAudience.Circle(circleId, "")
        val attributes = backend.attributes()
        val removed = mutableSetOf<Uuid>()

        attributes.profileCards()
            .filter { it.audience.isSameAs(target) }
            .forEach { card ->
                backend.deleteOrThrow(card.id, card.versionTag)
                removed += card.id
            }
        attributes.ownPhotoFor(target)?.let { photo ->
            backend.deleteOrThrow(photo.id, photo.versionTag)
            removed += photo.id
        }

        val known = backend.circles().let { list ->
            if (list.any { compareStringUuId(it.id, circleId) }) list else list + CardCircle(circleId, "")
        }
        attributes
            .filter { it.id !in removed && it.type != ProfileAttributeTypes.PROFILE_CARD }
            .filter { attribute -> attribute.acl.circleIdList.orEmpty().any { compareStringUuId(it, circleId) } }
            .forEach { attribute ->
                attribute.audience(known).shownOn(target, false, null)?.let { backend.saveAudience(attribute, it) }
            }

        backend.deleteCircle(Uuid.parse(circleId), removeMembers)
    }
}

private suspend fun UserCircleBackend.deleteOrThrow(id: Uuid, versionTag: Uuid) =
    check(deleteAttribute(id, versionTag)) { "Could not delete profile attribute $id" }

class DefaultUserCircleBackend(
    private val connections: ConnectionService,
    private val profile: ProfileRepository,
) : UserCircleBackend {
    override suspend fun createCircle(request: CreateAppCircleRequest) = connections.createCircle(request)

    override suspend fun deleteCircle(circleId: Uuid, removeMembers: Boolean) =
        connections.deleteCircle(circleId, removeMembers)

    override suspend fun circles(): List<CardCircle> = contactsCircles(connections.circles.value.circles)

    override suspend fun attributes(): List<ProfileAttribute> = profile.loadAttributes()

    override suspend fun saveAudience(attribute: ProfileAttribute, audience: ProfileAudience) =
        profile.saveWithAudience(attribute.type, attribute.data, audience, attribute)

    override suspend fun deleteAttribute(id: Uuid, versionTag: Uuid) = profile.delete(id, versionTag)
}
