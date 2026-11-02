package id.homebase.core.ui.screens.card

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
            """{"palette":"x","socials":"bar","portraits":[{"shape":"circle","tilt":2.5,"tape":"true","mono":"false"},"junk"],""" +
                """"blocks":[{"presentation":"row"},{"kind":"links","presentation":7},{"kind":"posts"}]}""",
        ).jsonObject
        val read = CardOverrides.fromJson(stored)

        assertNull(read.palette)
        assertEquals("bar", read.socials)
        assertNull(read.portraits!!.first().tape)
        assertNull(read.portraits.first().mono)
        assertEquals(listOf(CardPortrait(shape = "circle", tilt = 2.5)), read.portraits)
        assertEquals(listOf(CardBlock("links"), CardBlock("posts")), read.blocks)

        val store = FakeCardStore(listOf(profileCardAttribute(buildJsonObject { put("design", "board"); put("overrides", stored) })))
        assertTrue(CardRepository(store).savePublic(CardDesign.BOARD))
        val kept = store.writes.single().data["overrides"]!!.jsonObject
        assertEquals("bar", kept["socials"]!!.jsonPrimitive.content)
        assertEquals(
            """[{"shape":"circle","tilt":2.5}]""",
            kept["portraits"].toString(),
        )
    }

    private fun fieldOf(option: CardOption): String = when (option) {
        CardOption.COLOURS -> "palette.ground"
        CardOption.ACCENT -> "palette.accent"
        CardOption.DISPLAY_FONT -> "type.display"
        CardOption.TEXT_FONT -> "type.text"
        CardOption.PORTRAIT_SHAPE -> "portraits[].shape"
        CardOption.SOCIALS_STYLE -> "socials"
        CardOption.BLOCK_ORDER -> "blocks[].kind"
        CardOption.BLOCK_STYLE -> "blocks[].presentation"
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
                val path = fieldOf(option)
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
        assertEquals(listOf(CardBlock("links"), CardBlock("chat"), CardBlock("moments"), CardBlock("posts", "row")), poster.blocks)
        assertEquals("handles", poster.socials)

        val collage = everything.prunedFor(CardDesign.COLLAGE)
        assertEquals(CardPalette(accent = "#ABCDEF"), collage.palette)
        assertEquals(listOf(CardBlock("links"), CardBlock("chat"), CardBlock("moments"), CardBlock("posts", "row")), collage.blocks)
        assertEquals(listOf(CardPortrait(shape = "square"), CardPortrait()), collage.portraits)

        val dossier = everything.prunedFor(CardDesign.DOSSIER)
        assertEquals(CardPalette(accent = "#ABCDEF"), dossier.palette)
        assertEquals(listOf(CardPortrait(shape = "square")), dossier.portraits)

        assertEquals(CardOverrides.EMPTY, everything.prunedFor("hologram"))
    }

    @Test
    fun orderToolOnlyOffersWhatTheDesignReorders() {
        val collage = CardDesignSpecs.of(CardDesign.COLLAGE)!!
        assertEquals(listOf("links", "moments"), collage.reorderable(hasLinks = true))
        assertEquals(listOf("moments"), collage.reorderable(hasLinks = false))
        assertEquals(listOf("chat", "links", "moments"), CardDesignSpecs.of(CardDesign.POSTER)!!.reorderable(hasLinks = true))

        val stored = CardOverrides.EMPTY.withBlockOrder(listOf("links", "chat", "moments"))
        val shown = listOf("links", "moments")
        val reordered = stored.withShownBlockOrder(shown, listOf("moments", "links"))
        assertEquals(listOf("moments", "chat", "links"), reordered.blockOrder())
    }

    @Test
    fun blockStyleIsSetOnEverySectionAndClearsBackToThePreset() {
        val styled = CardOverrides.EMPTY.with(CardOption.BLOCK_STYLE, "boxed")
        assertEquals(listOf("chat", "links", "moments", "posts").map { CardBlock(it, "boxed") }, styled.blocks)
        assertEquals("boxed", styled.valueOf(CardOption.BLOCK_STYLE))

        val cleared = styled.with(CardOption.BLOCK_STYLE, null)
        assertNull(cleared.valueOf(CardOption.BLOCK_STYLE))
        assertNull(cleared.blocks)

        assertNull(CardOverrides.EMPTY.valueOf(CardOption.BLOCK_STYLE))
        assertNull(everything.valueOf(CardOption.BLOCK_STYLE))
    }

    @Test
    fun reorderingKeepsEachSectionsStyle() {
        val styled = CardOverrides(blocks = listOf(CardBlock("chat", "button"), CardBlock("links", "row"))).withBlockOrder(listOf("links", "chat", "moments"))
        assertEquals(
            listOf(CardBlock("links", "row"), CardBlock("chat", "button"), CardBlock("moments"), CardBlock("posts")),
            styled.blocks,
        )
        assertEquals(
            listOf("moments", "chat", "links"),
            styled.withShownBlockOrder(listOf("links", "moments"), listOf("moments", "links")).blockOrder(),
        )
    }

    @Test
    fun everyDesignKeepsTheBlockStyleAndItsOrder() {
        val styled = CardOverrides.EMPTY.withBlockOrder(listOf("links", "chat", "moments")).with(CardOption.BLOCK_STYLE, "row")
        for (design in CardDesign.all) {
            assertTrue(CardOption.BLOCK_STYLE in CardDesignSpecs.of(design)!!.options, design)
            val pruned = styled.prunedFor(design)
            assertEquals("row", pruned.valueOf(CardOption.BLOCK_STYLE), design)
            assertEquals(listOf("links", "chat", "moments"), pruned.blockOrder(), design)
        }
        assertEquals(CardOverrides.EMPTY, styled.prunedFor("hologram"))
    }

    @Test
    fun stylingThenResettingABoardCardLeavesItsPresetOrder() {
        val board = CardDesignSpecs.of(CardDesign.BOARD)!!
        val styled = CardOverrides.EMPTY.with(CardOption.BLOCK_STYLE, "row", board.presetOrder)
        assertEquals(listOf("links", "moments", "chat"), styled.blockOrder())
        assertEquals("row", styled.valueOf(CardOption.BLOCK_STYLE))

        val reset = styled.with(CardOption.BLOCK_STYLE, null, board.presetOrder)
        assertNull(reset.blocks)
        assertNull(CardOverrides.EMPTY.with(CardOption.BLOCK_STYLE, null, board.presetOrder).blocks)
    }

    @Test
    fun stylingKeepsACustomOrderAndResettingKeepsIt() {
        val custom = CardOverrides.EMPTY.withBlockOrder(listOf("chat", "moments", "links"))
        val reset = custom.with(CardOption.BLOCK_STYLE, "row", listOf("links", "moments", "chat"))
            .with(CardOption.BLOCK_STYLE, null, listOf("links", "moments", "chat"))
        assertEquals(listOf("chat", "moments", "links"), reset.blockOrder())
    }
}
