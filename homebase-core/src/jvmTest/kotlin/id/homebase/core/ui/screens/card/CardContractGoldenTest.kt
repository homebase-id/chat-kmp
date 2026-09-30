package id.homebase.core.ui.screens.card

import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.client.profile.SaveProfileAttributeRequest
import id.homebase.api.serialization.OdinSystemSerializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Writes the JSON chat-kmp puts on the wire for the profile-card contract and pins it against the
 * copies checked in beside the odin-core and odin-js tests. Regenerate with CARD_GOLDEN_UPDATE=1.
 */
class CardContractGoldenTest {

    private val pretty = Json { prettyPrint = true }

    private val publicId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val publicTag = Uuid.parse("22222222-2222-4222-8222-222222222222")
    private val friendsCircle = "33333333333343338333333333333333"
    private val familyCircle = "44444444444443338444444444444444"

    private val overrides = buildJsonObject {
        put("palette", buildJsonObject {
            put("ground", "#112233")
            put("ink", "#F0F0F0")
            put("muted", "#99AABB")
            put("accent", "#ABCDEF")
            put("surface", "#223344")
            put("surfaceInk", "#FFFFFF")
        })
        put("type", buildJsonObject {
            put("display", "caveat")
            put("text", "newsreader")
            put("label", "space-mono")
            put("displayCase", "upper")
        })
        put("portraits", buildJsonArray {
            add(buildJsonObject {
                put("source", "photo")
                put("shape", "square")
                put("ring", 2)
                put("shadow", "soft")
                put("tilt", 5)
                put("tape", true)
                put("mono", true)
            })
        })
        put("blocks", buildJsonArray {
            add(buildJsonObject { put("kind", "posts"); put("presentation", "row") })
            add(buildJsonObject { put("kind", "links"); put("presentation", "button") })
        })
        put("socials", "handles")
    }

    private fun request(
        card: ProfileCard,
        visibility: ProfileVisibility,
        edit: Boolean = false,
    ): JsonElement = OdinSystemSerializer.json.parseToJsonElement(
        OdinSystemSerializer.json.encodeToString(
            SaveProfileAttributeRequest(
                type = ProfileAttributeTypes.PROFILE_CARD,
                id = if (edit) card.id else null,
                expectedVersionTag = if (edit) card.versionTag else null,
                visibility = visibility.wireValue,
                data = card.toData(),
                priority = card.priority,
            ),
        ),
    )

    private fun golden(name: String, actual: JsonElement) {
        val text = pretty.encodeToString(JsonElement.serializer(), actual) + "\n"
        if (System.getenv("CARD_GOLDEN_UPDATE") == "1") {
            File("src/jvmTest/resources/card-contract/$name").writeText(text)
        }
        val checkedIn = assertNotNull(
            javaClass.classLoader.getResource("card-contract/$name"),
            "missing fixture $name; run with CARD_GOLDEN_UPDATE=1",
        ).readText()
        assertEquals(checkedIn, text, "fixture $name is stale; run with CARD_GOLDEN_UPDATE=1 and copy it to odin-core and odin-js")
    }

    private val publicCard = ProfileCard(
        id = publicId,
        versionTag = publicTag,
        audience = CardAudience.Public,
        design = "board",
        overrides = overrides,
        priority = PUBLIC_CARD_PRIORITY,
        extra = buildJsonObject { put("futureKey", "kept") },
    )

    private fun circleCard(id: String, label: String, design: String, priority: Int) = ProfileCard(
        id = Uuid.random(),
        versionTag = Uuid.random(),
        audience = CardAudience.Circle(id, label),
        design = design,
        overrides = if (priority == 0) overrides else JsonObject(emptyMap()),
        priority = priority,
    )

    @Test
    fun savePublicCardCreate() {
        golden("save-public-card.json", request(publicCard, ProfileVisibility.ANONYMOUS))
    }

    @Test
    fun savePublicCardEdit() {
        golden("save-public-card-edit.json", request(publicCard, ProfileVisibility.ANONYMOUS, edit = true))
    }

    @Test
    fun saveCircleCard() {
        golden("save-circle-card.json", request(circleCard(friendsCircle, "Friends", "dossier", 0), ProfileVisibility.CONNECTED))
    }

    @Test
    fun savePublicThroughTheRepositoryWritesTheSameDataAndPriority() = runTest {
        class Store : CardAttributeStore {
            var data: JsonObject? = null
            var priority: Int? = null
            var visibility: ProfileVisibility? = null
            override suspend fun load() = emptyList<ProfileAttribute>()
            override suspend fun save(data: JsonObject, visibility: ProfileVisibility, id: Uuid?, versionTag: Uuid?, priority: Int) {
                this.data = data; this.visibility = visibility; this.priority = priority
            }
        }
        val store = Store()
        CardRepository(store).savePublic("board", overrides)
        assertEquals(publicCard.copy(extra = JsonObject(emptyMap())).toData(), store.data)
        assertEquals(PUBLIC_CARD_PRIORITY, store.priority)
        assertEquals(ProfileVisibility.ANONYMOUS, store.visibility)
    }

    @Test
    fun storedSetIsWhatTheCardPickerReads() {
        val circles = listOf(
            circleCard(familyCircle, "Family", "collage", 5),
            circleCard(friendsCircle, "Friends", "dossier", 2),
        )
        val stored = buildJsonArray {
            for ((card, visibility) in listOf(publicCard to ProfileVisibility.ANONYMOUS) + circles.map { it to ProfileVisibility.CONNECTED }) {
                val circleId = (card.audience as? CardAudience.Circle)?.id
                add(buildJsonObject {
                    put("visibility", visibility.wireValue)
                    put("circleIds", JsonArray(listOfNotNull(circleId).map(::JsonPrimitive)))
                    put("priority", card.priority)
                    put("data", card.toData())
                })
            }
        }
        golden("stored-card-set.json", stored)

        val roundTripped = listOf(publicCard to ProfileVisibility.ANONYMOUS)
            .plus(circles.map { it to ProfileVisibility.CONNECTED })
            .map { (card, visibility) ->
                val circleId = (card.audience as? CardAudience.Circle)?.id
                ProfileAttribute(
                    id = card.id,
                    type = ProfileAttributeTypes.PROFILE_CARD,
                    versionTag = card.versionTag,
                    visibility = visibility,
                    data = card.toData(),
                    acl = AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = listOfNotNull(circleId)),
                    priority = card.priority,
                )
            }.profileCards()
        assertEquals(3, roundTripped.size)
        assertEquals(overrides, roundTripped.first { it.audience is CardAudience.Public }.overrides)
    }

    @Test
    fun renderPayload() {
        val payload = CardPayload(
            design = "board",
            data = CardData(
                odinId = "frodo.dotyou.cloud",
                firstName = "Frodo",
                surName = "Baggins",
                displayName = "Frodo Baggins",
                headline = "Ring bearer",
                bio = "Short bio",
                photo = CardImage("data:image/png;base64,iVBORw0KGgo="),
                links = listOf(CardLink("1", "Site", "https://example.com")),
                socials = listOf(CardSocial("twitter", "frodo")),
            ),
        )
        golden("render-payload.json", OdinSystemSerializer.json.parseToJsonElement(payload.toJson()))
    }
}
