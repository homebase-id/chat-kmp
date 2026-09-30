package id.homebase.core.ui.screens.card

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class CardOverridesTest {

    private val everything = CardOverrides(
        palette = CardPalette(ground = "#112233", accent = "#ABCDEF"),
        type = CardTypeface(display = "caveat", text = "newsreader", label = "space-mono", displayCase = "upper"),
        portraits = listOf(CardPortrait(source = "photo", shape = "square", ring = 2.0, tilt = -5.0)),
        blocks = listOf(CardBlock("posts", "row"), CardBlock("links")),
        socials = "handles",
    )

    // Same object the web's applyOverrides reads; keep in step with odin-js cards/overrides.ts.
    private val golden = """{"palette":{"ground":"#112233","accent":"#ABCDEF"},""" +
        """"type":{"display":"caveat","text":"newsreader","label":"space-mono","displayCase":"upper"},""" +
        """"portraits":[{"source":"photo","shape":"square","ring":2,"tilt":-5}],""" +
        """"blocks":[{"kind":"posts","presentation":"row"},{"kind":"links"}],"socials":"handles"}"""

    @Test
    fun overridesSerialiseToExactlyTheWebSchema() {
        assertEquals(golden, cardJson.encodeToString(CardOverrides.serializer(), everything))
        assertEquals(everything, CardOverrides.fromJson(cardJson.parseToJsonElement(golden).jsonObject))
    }

    @Test
    fun theRenderPayloadCarriesOverridesAndAudienceAtTheTopLevel() {
        val payload = CardPayload(
            design = "board",
            data = CardData(odinId = "frodo.dotyou.cloud"),
            audience = CardAudiencePayload("circle", "Friends"),
            overrides = CardOverrides(socials = "bar"),
        )
        val json = cardJson.parseToJsonElement(payload.toJson()).jsonObject
        assertEquals(setOf("design", "data", "audience", "overrides"), json.keys)
        assertEquals(JsonPrimitive("bar"), json.getValue("overrides").jsonObject["socials"])
    }

    @Test
    fun noOverridesIsOmittedFromThePayload() {
        val json = CardPayload("board", CardData(odinId = "a.b")).toJson()
        assertTrue("overrides" !in cardJson.parseToJsonElement(json).jsonObject)
    }

    @Test
    fun unknownStoredKeysAndUndecodableValuesReadAsWhatTheyCanBe() {
        val future = buildJsonObject { put("socials", "bar"); put("sparkle", "yes") }
        assertEquals(CardOverrides(socials = "bar"), CardOverrides.fromJson(future))
    }

    @Test
    fun oneUndecodableValueDropsOnlyItselfAndASaveKeepsTheSiblings() = runTest {
        val stored = cardJson.parseToJsonElement(
            """{"palette":"x","socials":"bar","portraits":[{"shape":"circle","tilt":2.5},"junk"],""" +
                """"blocks":[{"presentation":"row"},{"kind":"links","presentation":7},{"kind":"posts"}]}""",
        ).jsonObject
        val read = CardOverrides.fromJson(stored)

        assertNull(read.palette)
        assertEquals("bar", read.socials)
        assertEquals(listOf(CardPortrait(shape = "circle", tilt = 2.5)), read.portraits)
        assertEquals(listOf(CardBlock("links"), CardBlock("posts")), read.blocks)

        val store = FakeStore(stored)
        assertTrue(CardRepository(store).savePublic(CardDesign.BOARD))
        val kept = store.written.single()["overrides"]!!.jsonObject
        assertEquals("bar", kept["socials"]!!.jsonPrimitive.content)
        assertEquals(
            """[{"shape":"circle","tilt":2.5}]""",
            kept["portraits"].toString(),
        )
    }

    private class FakeStore(overrides: kotlinx.serialization.json.JsonObject) : CardAttributeStore {
        val written = mutableListOf<kotlinx.serialization.json.JsonObject>()
        private val attribute = id.homebase.api.client.profile.ProfileAttribute(
            id = kotlin.uuid.Uuid.random(),
            type = id.homebase.api.client.profile.ProfileAttributeTypes.PROFILE_CARD,
            versionTag = kotlin.uuid.Uuid.random(),
            visibility = id.homebase.api.client.profile.ProfileVisibility.ANONYMOUS,
            data = buildJsonObject { put("design", "board"); put("overrides", overrides) },
        )
        override suspend fun load() = listOf(attribute)
        override suspend fun save(
            data: kotlinx.serialization.json.JsonObject,
            visibility: id.homebase.api.client.profile.ProfileVisibility,
            id: kotlin.uuid.Uuid?,
            versionTag: kotlin.uuid.Uuid?,
            priority: Int,
        ) {
            written += data
        }
    }

    private fun resolves(descriptor: SerialDescriptor, path: String): Boolean {
        var current = descriptor
        for (part in path.split(".")) {
            val list = part.endsWith("[]")
            val index = current.getElementIndex(part.removeSuffix("[]"))
            if (index < 0) return false
            current = current.getElementDescriptor(index).let { if (list) it.getElementDescriptor(0) else it }
        }
        return true
    }

    @Test
    fun everyDeclaredOptionMapsToAFieldOfCardOverrides() {
        for (design in CardDesign.all) {
            val spec = requireNotNull(CardDesignSpecs.of(design)) { "no spec for $design" }
            assertTrue(spec.options.isNotEmpty(), design)
            for (option in spec.options) {
                val path = CardDesignSpecs.fieldOf(option)
                assertTrue(resolves(CardOverrides.serializer().descriptor, path), "$design/$option -> $path")
            }
            assertEquals(spec.options.contains(CardOption.PORTRAIT_SHAPE), spec.portraitSlots > 0, design)
        }
    }

    @Test
    fun aDesignKeepsOnlyTheOptionsItExposes() {
        val poster = everything.prunedFor(CardDesign.POSTER)
        assertNull(poster.palette)
        assertNull(poster.portraits)
        assertEquals(CardTypeface(display = "caveat", text = "newsreader"), poster.type)
        assertEquals(listOf(CardBlock("posts"), CardBlock("links")), poster.blocks)
        assertEquals("handles", poster.socials)

        val collage = everything.prunedFor(CardDesign.COLLAGE)
        assertEquals(CardPalette(accent = "#ABCDEF"), collage.palette)
        assertEquals(listOf(CardBlock("posts"), CardBlock("links")), collage.blocks)
        assertEquals(listOf(CardPortrait(shape = "square"), CardPortrait()), collage.portraits)

        val dossier = everything.prunedFor(CardDesign.DOSSIER)
        assertEquals(CardPalette(accent = "#ABCDEF"), dossier.palette)
        assertEquals(listOf(CardPortrait(shape = "square")), dossier.portraits)

        assertEquals(CardOverrides.EMPTY, everything.prunedFor("hologram"))
    }

    @Test
    fun buildingAPayloadPrunesForTheDesignBeingRendered() {
        val payload = buildCardPayload(
            odinId = "frodo.dotyou.cloud",
            attributes = emptyList(),
            design = CardDesign.POSTER,
            photoSrc = null,
            headerSrc = null,
            tagLine = null,
            overrides = CardOverrides(palette = CardPalette(accent = "#ABCDEF")),
        )
        assertNull(payload.overrides)
    }
}
