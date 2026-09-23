package id.homebase.core.ui.screens.card

import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

class CardSiteDefaultsTest {

    private val odinId = OdinId("frodo.baggins.demo.rocks")

    // Trimmed from a live https://frodo.baggins.demo.rocks/cdn/sitedata.json.
    private val statusSection = """{"name":"status","files":[{"header":{"fileId":"d028df18-e0f0-2500-89d3-c0e5159fdeac","driveId":"8f12d8c4-9338-13d3-7848-8d91ed23b64c","fileMetadata":{"updated":1777366791810,"isEncrypted":false,"payloads":[],"appData":{"fileType":77,"content":"{\"type\":\"9acb44549b41563697bb490144ec6258\",\"priority\":10000,\"data\":{\"status\":\"Bag End, the Shire\"}}"}}},"payloads":[]}]}"""

    private fun themeSection(data: String): String {
        val content = """{"type":"8f7eb1c32fc72c0abf0cee09be588f26","priority":1000,"sectionId":"ce23fd9d07e126739c132210d5ae189b","data":$data}"""
        return """{"name":"theme","files":[{"header":{"fileId":"d128df18-4053-1000-86c8-fc7c0c963ae2","targetDrive":{"alias":"ec83345af6a747d4404ef8b0f8844caa","type":"597241530e3ef24b28b9a75ec3a5c45c"},"driveId":"ec83345a-f6a7-47d4-404e-f8b0f8844caa","fileMetadata":{"updated":1789658146611,"isEncrypted":false,"payloads":[{"key":"headr_key","contentType":"image/jpeg","bytesWritten":2174295,"lastModified":1742564961147}],"appData":{"fileType":77,"content":${JsonPrimitive(content)}}},"serverMetadata":{"accessControlList":{"requiredSecurityGroup":"anonymous"}}},"payloads":[]}]}"""
    }

    private val posterTheme =
        themeSection("""{"themeId":"555","isProtected":true,"tagLine":"Ring-bearer","headerImageKey":"headr_key"}""")

    private fun siteDataText(vararg sections: String) = "[${sections.joinToString(",")}]"

    private fun siteData(vararg sections: String): JsonArray =
        cardJson.parseToJsonElement(siteDataText(*sections)).jsonArray

    private fun statusRecord(tier: ProfileVisibility, status: String) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.STATUS,
        versionTag = Uuid.random(),
        visibility = tier,
        data = JsonObject(mapOf(ProfileAttributeTypes.KEY_STATUS to JsonPrimitive(status))),
    )

    private fun headline(tagLine: String?, status: String?) =
        buildCardPayload(
            odinId = odinId.toString(),
            attributes = listOfNotNull(
                status?.let { statusRecord(ProfileVisibility.ANONYMOUS, it) },
                statusRecord(ProfileVisibility.CONNECTED, "Vetted status"),
            ),
            design = CardDesign.BOARD,
            photoSrc = null,
            headerSrc = null,
            tagLine = tagLine,
        ).data.headline

    private fun repository(serve: () -> Pair<HttpStatusCode, String>): Pair<PublicIdentityRepository, List<String>> {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url}"
            val (status, body) = serve()
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PublicIdentityRepository(HttpClient(engine)) to requests
    }

    @Test
    fun cardThemesMapToTheirDesign() {
        assertEquals(CardDesign.POSTER, cardDesignForTheme("555"))
        assertEquals(CardDesign.BOARD, cardDesignForTheme("666"))
        assertEquals(CardDesign.COLLAGE, cardDesignForTheme("777"))
        assertEquals(CardDesign.DOSSIER, cardDesignForTheme("888"))
    }

    @Test
    fun disabledLegacyAndGarbageThemesFallBackToBoard() {
        listOf("0", "111", "222", "333", "444", "999", "", " 555", "poster", "null", null).forEach {
            assertEquals(CardDesign.BOARD, cardDesignForTheme(it), "themeId=$it")
        }
    }

    @Test
    fun tagLineWinsOverStatus() {
        assertEquals("Ring-bearer", headline(tagLine = "Ring-bearer", status = "Bag End"))
        assertEquals("Ring-bearer", headline(tagLine = "  Ring-bearer \n", status = null))
    }

    @Test
    fun missingOrBlankTagLineFallsBackToStatus() {
        assertEquals("Bag End", headline(tagLine = null, status = "Bag End"))
        assertEquals("Bag End", headline(tagLine = " \n ", status = "Bag End"))
        assertNull(headline(tagLine = null, status = null))
    }

    @Test
    fun cardThemeGivesDesignTagLineAndHeader() {
        val defaults = assertNotNull(cardSiteDefaults(siteData(statusSection, posterTheme)))
        assertEquals(CardDesign.POSTER, defaults.design)
        assertEquals("Ring-bearer", defaults.tagLine)
        val header = assertNotNull(defaults.header)
        assertEquals(SystemDriveConstants.homePageConfigDrive.alias, header.driveId)
        assertEquals(Uuid.parse("d128df18-4053-1000-86c8-fc7c0c963ae2"), header.fileId)
        assertEquals("headr_key", header.payloadKey)
        assertEquals(1789658146611L, header.lastModified)
        assertFalse(header.isEncrypted)
        assertFalse(header.isOverPeer)
    }

    @Test
    fun sitedataWithoutTemplateSettingsHasNoDefaults() {
        assertNull(cardSiteDefaults(siteData(statusSection)))
        assertNull(cardSiteDefaults(siteData(statusSection, """{"name":"theme","files":[]}""")))
        assertNull(cardSiteDefaults(siteData()))
    }

    @Test
    fun themeWithoutTagLineOrHeaderKeepsOnlyTheDesign() {
        val defaults = assertNotNull(cardSiteDefaults(siteData(themeSection("""{"themeId":"777","isProtected":true}"""))))
        assertEquals(CardSiteDefaults(design = CardDesign.COLLAGE), defaults)
        val blank = assertNotNull(cardSiteDefaults(siteData(themeSection("""{"themeId":"888","tagLine":"  ","headerImageKey":""}"""))))
        assertEquals(CardSiteDefaults(design = CardDesign.DOSSIER), blank)
    }

    @Test
    fun legacyOrDisabledThemeFallsBackToBoardButKeepsTagLineAndHeader() {
        val legacy = assertNotNull(cardSiteDefaults(siteData(themeSection("""{"themeId":"222","tagLine":"Hi","headerImageKey":"headr_key"}"""))))
        assertEquals(CardDesign.BOARD, legacy.design)
        assertEquals("Hi", legacy.tagLine)
        assertEquals("headr_key", legacy.header?.payloadKey)
        assertEquals(CardDesign.BOARD, assertNotNull(cardSiteDefaults(siteData(themeSection("""{"themeId":"0"}""")))).design)
        assertEquals(CardDesign.BOARD, assertNotNull(cardSiteDefaults(siteData(themeSection("""{}""")))).design)
    }

    @Test
    fun malformedThemeSectionIsIgnored() {
        assertNull(cardSiteDefaults(siteData("""{"name":"theme","files":[{"header":{"fileMetadata":{"appData":{"content":"not json"}}}}]}""")))
        assertNull(cardSiteDefaults(siteData("""{"name":"theme","files":[{"header":{"fileMetadata":{"appData":{"content":"[1,2]"}}}}]}""")))
        assertNull(cardSiteDefaults(siteData("\"garbage\"", posterTheme)))
    }

    @Test
    fun repositoryReadsThemeFromPublicSitedataOnly() = runTest {
        val (repository, requests) = repository { HttpStatusCode.OK to siteDataText(statusSection, posterTheme) }
        val defaults = repository.loadCardSiteDefaults(odinId)
        assertEquals(CardDesign.POSTER, defaults.design)
        assertEquals("Ring-bearer", defaults.tagLine)
        assertEquals("headr_key", defaults.header?.payloadKey)
        assertEquals(listOf("${HttpMethod.Get.value} https://frodo.baggins.demo.rocks/cdn/sitedata.json"), requests)
    }

    @Test
    fun repositoryFallsBackToBoardAndStatusWhenThemeIsMissing() = runTest {
        val (unreachable, _) = repository { HttpStatusCode.NotFound to "" }
        assertEquals(CardSiteDefaults(), unreachable.loadCardSiteDefaults(odinId))
        assertEquals(CardSiteDefaults(), unreachable.loadCardSiteDefaults(odinId))

        val (noTheme, _) = repository { HttpStatusCode.OK to siteDataText(statusSection) }
        val defaults = noTheme.loadCardSiteDefaults(odinId)
        assertEquals(CardSiteDefaults(), defaults)
        assertEquals("Bag End", headline(tagLine = defaults.tagLine, status = "Bag End"))
    }
}
