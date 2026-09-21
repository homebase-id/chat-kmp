package id.homebase.core.ui.screens.card

import id.homebase.api.client.profile.Link
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileCard
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.client.profile.SameAs
import id.homebase.core.ui.screens.profile.ProfileEditUiState
import id.homebase.core.ui.screens.profile.ProfileField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class CardProfileDataTest {

    private val odinId = "frodo.dotyou.cloud"

    private fun payload(
        state: ProfileEditUiState,
        tier: ProfileVisibility = ProfileVisibility.ANONYMOUS,
        publicProfile: ProfileCard? = null,
        photoSrc: String? = null,
        headerSrc: String? = null,
        tagLine: String? = null,
    ) = buildCardPayload(odinId, state, tier, CardDesign.BOARD, publicProfile, photoSrc, headerSrc, tagLine)

    private fun profileCard(
        bioSummary: String? = null,
        links: List<Link> = emptyList(),
        sameAs: List<SameAs> = emptyList(),
    ) = ProfileCard(
        image = "https://frodo.dotyou.cloud/pub/image",
        givenName = "Frodo",
        familyName = "Baggins",
        status = null,
        name = "Frodo Baggins",
        bio = "The full bio, which the card never shows.",
        bioSummary = bioSummary,
        links = links,
        email = emptyList(),
        sameAs = sameAs,
    )

    private fun photo(visibility: ProfileVisibility) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.PHOTO,
        versionTag = Uuid.random(),
        visibility = visibility,
        data = JsonObject(emptyMap()),
    )

    private fun socials(vararg values: Pair<ProfileField, String>) =
        payload(ProfileEditUiState(anonymousValues = mapOf(*values))).data.socials

    @Test
    fun publicTierIgnoresConnectedValues() {
        val state = ProfileEditUiState(
            anonymousValues = mapOf(
                ProfileField.GIVEN_NAME to "Frodo",
                ProfileField.SURNAME to "Baggins",
                ProfileField.STATUS to "Public status",
            ),
            connectedValues = mapOf(
                ProfileField.GIVEN_NAME to "Mr. Frodo",
                ProfileField.STATUS to "Vetted status",
                ProfileField.TWITTER to "frodo_vetted",
            ),
        )
        val data = payload(state, ProfileVisibility.ANONYMOUS).data
        assertEquals("Frodo", data.firstName)
        assertEquals("Baggins", data.surName)
        assertEquals("Frodo Baggins", data.displayName)
        assertEquals("Public status", data.headline)
        assertEquals(emptyList(), data.socials)
    }

    @Test
    fun vettedTierPrefersConnectedAndFallsBackToPublicPerField() {
        val state = ProfileEditUiState(
            anonymousValues = mapOf(
                ProfileField.GIVEN_NAME to "Frodo",
                ProfileField.SURNAME to "Baggins",
                ProfileField.STATUS to "Public status",
                ProfileField.INSTAGRAM to "frodo_public",
            ),
            connectedValues = mapOf(
                ProfileField.GIVEN_NAME to "Mr. Frodo",
                ProfileField.SURNAME to "   ",
                ProfileField.TWITTER to "frodo_vetted",
            ),
        )
        val data = payload(state, ProfileVisibility.CONNECTED).data
        assertEquals("Mr. Frodo", data.firstName)
        assertEquals("Baggins", data.surName)
        assertEquals("Mr. Frodo Baggins", data.displayName)
        assertEquals("Public status", data.headline)
        assertEquals(
            listOf(CardSocial("twitter", "frodo_vetted"), CardSocial("instagram", "frodo_public")),
            data.socials,
        )
    }

    @Test
    fun photoTierFallsBackToPublicOnlyAboveThePublicTier() {
        val public = photo(ProfileVisibility.ANONYMOUS)
        val vetted = photo(ProfileVisibility.CONNECTED)

        val both = ProfileEditUiState(anonymousPhoto = public, connectedPhoto = vetted)
        assertSame(public, both.visiblePhoto(ProfileVisibility.ANONYMOUS))
        assertSame(vetted, both.visiblePhoto(ProfileVisibility.CONNECTED))

        val publicOnly = ProfileEditUiState(anonymousPhoto = public)
        assertSame(public, publicOnly.visiblePhoto(ProfileVisibility.CONNECTED))

        val vettedOnly = ProfileEditUiState(connectedPhoto = vetted)
        assertNull(vettedOnly.visiblePhoto(ProfileVisibility.ANONYMOUS))
        assertSame(vetted, vettedOnly.visiblePhoto(ProfileVisibility.CONNECTED))
    }

    @Test
    fun imagesPassThroughAndBlankImagesDropOut() {
        val state = ProfileEditUiState()
        val data = payload(state, photoSrc = "data:image/jpeg;base64,AAAA", headerSrc = "data:image/jpeg;base64,BBBB").data
        assertEquals(CardImage("data:image/jpeg;base64,AAAA"), data.photo)
        assertEquals(CardImage("data:image/jpeg;base64,BBBB"), data.header)

        val blank = payload(state, photoSrc = "", headerSrc = "  ").data
        assertNull(blank.photo)
        assertNull(blank.header)
    }

    @Test
    fun blankFieldsDropOutAndValuesAreTrimmed() {
        val state = ProfileEditUiState(
            anonymousValues = mapOf(
                ProfileField.GIVEN_NAME to "  Frodo ",
                ProfileField.SURNAME to "",
                ProfileField.STATUS to " \n ",
                ProfileField.TWITTER to "   ",
                ProfileField.LINKEDIN to "",
            ),
        )
        val data = payload(state, publicProfile = profileCard(bioSummary = "  ")).data
        assertEquals("Frodo", data.firstName)
        assertNull(data.surName)
        assertEquals("Frodo", data.displayName)
        assertNull(data.headline)
        assertNull(data.bio)
        assertEquals(emptyList(), data.socials)
        assertEquals(emptyList(), data.links)
    }

    @Test
    fun emptyProfileIsJustTheOwner() {
        val data = payload(ProfileEditUiState(), ProfileVisibility.CONNECTED).data
        assertEquals(CardData(odinId = odinId), data)
    }

    @Test
    fun bioIsThePublicSummaryOnBothTiers() {
        val card = profileCard(bioSummary = " Ring-bearer. ")
        assertEquals("Ring-bearer.", payload(ProfileEditUiState(), ProfileVisibility.ANONYMOUS, card).data.bio)
        assertEquals("Ring-bearer.", payload(ProfileEditUiState(), ProfileVisibility.CONNECTED, card).data.bio)
    }

    @Test
    fun linksKeepOnlyHttpAndHttpsAndSkipTheSocialCopies() {
        val card = profileCard(
            links = listOf(
                Link(type = "twitter", url = "https://twitter.com/frodo"),
                Link(type = "Blog", url = "https://frodo.dotyou.cloud/posts"),
                Link(type = "Script", url = "javascript:alert(1)"),
                Link(type = "Script caps", url = "JavaScript://%0aalert(1)"),
                Link(type = "Inline", url = "data:text/html,<script>alert(1)</script>"),
                Link(type = "Files", url = "ftp://files.shire.me"),
                Link(type = "Mail", url = "mailto:frodo@shire.me"),
                Link(type = "Relative", url = "/posts"),
                Link(type = "No host", url = "https://"),
                Link(type = "Spaced", url = "https://exa mple.com"),
                Link(type = "Missing", url = null),
                Link(type = "Old site", url = " HTTP://shire.me/frodo "),
                Link(type = "  ", url = "https://example.com/there-and-back"),
            ),
            sameAs = listOf(SameAs(type = "twitter", url = "https://twitter.com/frodo")),
        )
        assertEquals(
            listOf(
                CardLink(id = "1", text = "Blog", target = "https://frodo.dotyou.cloud/posts"),
                CardLink(id = "2", text = "Old site", target = "HTTP://shire.me/frodo"),
                CardLink(id = "3", text = "https://example.com/there-and-back", target = "https://example.com/there-and-back"),
            ),
            payload(ProfileEditUiState(), publicProfile = card).data.links,
        )
    }

    @Test
    fun linksAreTheSameOnTheVettedTier() {
        val card = profileCard(links = listOf(Link(type = "Blog", url = "https://frodo.dotyou.cloud/posts")))
        assertEquals(
            payload(ProfileEditUiState(), ProfileVisibility.ANONYMOUS, card).data.links,
            payload(ProfileEditUiState(), ProfileVisibility.CONNECTED, card).data.links,
        )
    }

    @Test
    fun socialsUseOdinJsTypeStringsInProfileOrder() {
        assertEquals(
            listOf(
                CardSocial("twitter", "t"),
                CardSocial("facebook", "f"),
                CardSocial("instagram", "i"),
                CardSocial("tiktok", "k"),
                CardSocial("linkedin", "l"),
            ),
            socials(
                ProfileField.LINKEDIN to "l",
                ProfileField.TIKTOK to "k",
                ProfileField.INSTAGRAM to "i",
                ProfileField.FACEBOOK to "f",
                ProfileField.TWITTER to "t",
            ),
        )
    }

    @Test
    fun socialHandlesAreNormalised() {
        mapOf(
            "frodo" to "frodo",
            "  @frodo " to "frodo",
            "https://twitter.com/frodo" to "frodo",
            "https://x.com/frodo?s=21" to "frodo",
            "http://mobile.twitter.com/frodo/" to "frodo",
            "twitter.com/@frodo" to "frodo",
            "HTTPS://WWW.TWITTER.COM/Frodo#top" to "Frodo",
        ).forEach { (raw, expected) -> assertEquals(expected, socialUsername("twitter", raw), "twitter: $raw") }

        assertEquals("frodo.baggins", socialUsername("facebook", "frodo.baggins"))
        assertEquals("frodo.baggins", socialUsername("facebook", "https://m.facebook.com/frodo.baggins"))
        assertEquals("frodo", socialUsername("instagram", "https://www.instagram.com/frodo/"))
        assertEquals("frodo-baggins", socialUsername("linkedin", "https://www.linkedin.com/in/frodo-baggins/"))
        assertEquals("frodo-baggins", socialUsername("linkedin", "frodo-baggins"))
        assertEquals("frodo", socialUsername("tiktok", "frodo"))
        assertEquals("frodo", socialUsername("tiktok", "@frodo"))
        assertEquals("frodo", socialUsername("tiktok", "https://www.tiktok.com/@frodo?lang=en"))
    }

    @Test
    fun unusableSocialValuesDropOut() {
        assertNull(socialUsername("twitter", "https://example.com/frodo"))
        assertNull(socialUsername("twitter", "https://instagram.com/frodo"))
        assertNull(socialUsername("twitter", "https://twitter.com/"))
        assertNull(socialUsername("twitter", "@"))
        assertNull(socialUsername("twitter", "frodo baggins"))
        assertNull(socialUsername("linkedin", "https://linkedin.com/company/the-fellowship"))
        assertNull(socialUsername("linkedin", "https://linkedin.com/in/"))
        assertEquals(emptyList(), socials(ProfileField.TWITTER to "https://example.com/frodo"))
    }

    @Test
    fun builtPayloadSerialisesToTheContractFieldNames() {
        val state = ProfileEditUiState(
            anonymousValues = mapOf(
                ProfileField.GIVEN_NAME to "Frodo",
                ProfileField.SURNAME to "Baggins",
                ProfileField.STATUS to "Bag End, the Shire",
                ProfileField.INSTAGRAM to "@frodo",
            ),
        )
        val card = profileCard(
            bioSummary = "Ring-bearer.",
            links = listOf(Link(type = "Blog", url = "https://frodo.dotyou.cloud/posts")),
        )
        val built = buildCardPayload(
            odinId, state, ProfileVisibility.ANONYMOUS, CardDesign.POSTER, card,
            photoSrc = "data:image/jpeg;base64,AAAA", headerSrc = "data:image/jpeg;base64,BBBB", tagLine = null,
        )
        val json = cardJson.encodeToString(CardPayload.serializer(), built)
        val root = cardJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("design", "data"), root.keys)
        assertEquals(JsonPrimitive("poster"), root["design"])
        val data = root.getValue("data").jsonObject
        assertEquals(
            setOf(
                "odinId", "firstName", "surName", "displayName", "headline", "bio",
                "photo", "header", "links", "socials",
            ),
            data.keys,
        )
        assertEquals(JsonPrimitive(odinId), data["odinId"])
        assertEquals(JsonPrimitive("Frodo"), data["firstName"])
        assertEquals(JsonPrimitive("Baggins"), data["surName"])
        assertEquals(JsonPrimitive("Frodo Baggins"), data["displayName"])
        assertEquals(JsonPrimitive("Bag End, the Shire"), data["headline"])
        assertEquals(JsonPrimitive("Ring-bearer."), data["bio"])
        assertEquals(JsonObject(mapOf("src" to JsonPrimitive("data:image/jpeg;base64,AAAA"))), data["photo"])
        assertEquals(JsonObject(mapOf("src" to JsonPrimitive("data:image/jpeg;base64,BBBB"))), data["header"])
        assertEquals(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("1"),
                    "text" to JsonPrimitive("Blog"),
                    "target" to JsonPrimitive("https://frodo.dotyou.cloud/posts"),
                ),
            ),
            data.getValue("links").jsonArray.single(),
        )
        assertEquals(
            JsonObject(mapOf("type" to JsonPrimitive("instagram"), "username" to JsonPrimitive("frodo"))),
            data.getValue("socials").jsonArray.single(),
        )
        assertFalse("null" in json)
    }
}
