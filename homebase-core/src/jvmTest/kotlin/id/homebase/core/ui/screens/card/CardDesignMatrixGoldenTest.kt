package id.homebase.core.ui.screens.card

import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.ui.screens.profile.ProfileEditViewModel
import id.homebase.core.ui.screens.profile.ProfileField
import id.homebase.core.ui.screens.profile.visiblePhoto
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every design (including links) x public/circle x with/without overrides, built by the real buildCardPayload from real
 * attributes, plus the design specs. Regenerate with CARD_GOLDEN_UPDATE=1 and copy to odin-js.
 */
class CardDesignMatrixGoldenTest {

    private val pretty = Json { prettyPrint = true }
    private val friendsCircle = "33333333333343338333333333333333"

    private val overrides = CardOverrides(
        palette = CardPalette(ground = "#112233", ink = "#F0F0F0", muted = "#99AABB", accent = "#ABCDEF", surface = "#223344", surfaceInk = "#FFFFFF"),
        type = CardTypeface(display = "caveat", text = "newsreader", label = "space-mono", displayCase = "upper"),
        portraits = listOf(CardPortrait("photo", "rounded", ring = 2.0, shadow = "soft", tilt = 5.0, tape = true, mono = true)),
        blocks = listOf(CardBlock("posts", "row"), CardBlock("links", "button"), CardBlock("chat", "boxed")),
        socials = "handles",
    )

    private val designs = CardDesign.all

    private fun record(type: String, tier: ProfileVisibility, data: Map<String, JsonPrimitive>, circles: List<String> = emptyList()) = ProfileAttribute(
        id = Uuid.random(),
        type = type,
        versionTag = Uuid.random(),
        visibility = tier,
        data = JsonObject(data),
        acl = AccessControlList(requiredSecurityGroup = tier.wireValue, circleIdList = circles),
        priority = 0,
    )

    private fun fields(tier: ProfileVisibility, vararg values: Pair<ProfileField, String>): List<ProfileAttribute> {
        val byField = mapOf(*values)
        return ProfileEditViewModel.TYPE_FIELDS.mapNotNull { (type, fs) ->
            val data = fs.mapNotNull { (field, key) -> byField[field]?.let { key to JsonPrimitive(it) } }.toMap()
            if (data.isEmpty()) null else record(type, tier, data)
        }
    }

    private fun link(text: String, target: String, tier: ProfileVisibility, circles: List<String> = emptyList()) = record(
        ProfileAttributeTypes.LINK, tier,
        mapOf(ProfileAttributeTypes.KEY_LINK_TEXT to JsonPrimitive(text), ProfileAttributeTypes.KEY_LINK_TARGET to JsonPrimitive(target)),
        circles,
    )

    private fun photo(key: String, tier: ProfileVisibility, circles: List<String> = emptyList()) =
        record(ProfileAttributeTypes.PHOTO, tier, mapOf(ProfileAttributeTypes.KEY_PROFILE_IMAGE to JsonPrimitive(key)), circles)

    internal val attributes: List<ProfileAttribute> =
        fields(ProfileVisibility.ANONYMOUS, ProfileField.GIVEN_NAME to "Frodo", ProfileField.SURNAME to "Baggins", ProfileField.TWITTER to "frodo") +
            link("Public site", "https://public.example.com/a", ProfileVisibility.ANONYMOUS) +
            link("Friends only", "https://friends.example.com/b", ProfileVisibility.CONNECTED, listOf(friendsCircle)) +
            photo("public-key", ProfileVisibility.ANONYMOUS) +
            photo("friends-key", ProfileVisibility.CONNECTED, listOf(friendsCircle))

    private fun payload(audience: CardAudience, design: String, overrides: CardOverrides): JsonElement {
        val key = attributes.visiblePhoto(audience.aclFilter())?.data?.get(ProfileAttributeTypes.KEY_PROFILE_IMAGE)
        return OdinSystemSerializer.json.parseToJsonElement(
            buildCardPayload(
                odinId = "frodo.dotyou.cloud",
                attributes = attributes,
                design = design,
                photoSrc = key?.let { "data:image/jpeg;base64,${(it as JsonPrimitive).content}" },
                headerSrc = null,
                tagLine = "Ring bearer",
                audience = audience,
                overrides = overrides,
            ).toJson(),
        )
    }

    private fun golden(name: String, actual: JsonElement) {
        val text = pretty.encodeToString(JsonElement.serializer(), actual) + "\n"
        if (System.getenv("CARD_GOLDEN_UPDATE") == "1") {
            File("src/jvmTest/resources/card-contract/$name").writeText(text)
            return
        }
        val checkedIn = assertNotNull(
            javaClass.classLoader.getResource("card-contract/$name"),
            "missing fixture $name; run with CARD_GOLDEN_UPDATE=1",
        ).readText()
        assertEquals(checkedIn, text, "fixture $name is stale; run with CARD_GOLDEN_UPDATE=1 and copy it to odin-js")
    }

    @Test
    fun everyDesignAudienceAndOverridesCombination() {
        val audiences = listOf("public" to CardAudience.Public, "circle" to CardAudience.Circle(friendsCircle, "Friends"))
        for (design in designs) for ((audienceName, audience) in audiences) for ((variant, o) in listOf("overrides" to overrides, "plain" to CardOverrides.EMPTY)) {
            golden("matrix-$design-$audienceName-$variant.json", payload(audience, design, o))
        }
    }

    @Test
    fun circlePayloadCarriesTheCirclePhotoAndPublicKeepsTheAnonymousOne() {
        val circle = payload(CardAudience.Circle(friendsCircle, "Friends"), "board", CardOverrides.EMPTY) as JsonObject
        val public = payload(CardAudience.Public, "board", CardOverrides.EMPTY) as JsonObject
        fun src(p: JsonObject) = ((p["data"] as JsonObject)["photo"] as JsonObject)["src"]
        assertEquals(JsonPrimitive("data:image/jpeg;base64,friends-key"), src(circle))
        assertEquals(JsonPrimitive("data:image/jpeg;base64,public-key"), src(public))
    }

    @Test
    fun designSpecs() {
        val json = buildJsonObject {
            put("ids", buildJsonArray { designs.forEach { add(JsonPrimitive(it)) } })
            put("specs", buildJsonObject {
                for (design in designs) {
                    val spec = CardDesignSpecs.of(design)
                    put(design, if (spec == null) JsonPrimitive("none") else buildJsonObject {
                        put("options", buildJsonArray { spec.options.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) } })
                        put("portraitSlots", spec.portraitSlots)
                        put("schemes", buildJsonArray { spec.schemes.forEach { add(JsonPrimitive(it.id)) } })
                    })
                }
            })
            put("fonts", buildJsonArray { CardDesignSpecs.FONTS.forEach { add(JsonPrimitive(it)) } })
            put("portraitShapes", buildJsonArray { CardDesignSpecs.PORTRAIT_SHAPES.forEach { add(JsonPrimitive(it)) } })
            put("socialsStyles", buildJsonArray { CardDesignSpecs.SOCIALS_STYLES.forEach { add(JsonPrimitive(it)) } })
            put("blockKinds", buildJsonArray { CardDesignSpecs.BLOCK_KINDS.forEach { add(JsonPrimitive(it)) } })
            put("schemeGrounds", buildJsonObject {
                for (design in designs) CardDesignSpecs.of(design)?.let { s -> put(design, buildJsonArray { s.schemes.forEach { add(JsonPrimitive(it.ground)) } }) }
            })
        }
        golden("design-specs.json", json)
    }
}
