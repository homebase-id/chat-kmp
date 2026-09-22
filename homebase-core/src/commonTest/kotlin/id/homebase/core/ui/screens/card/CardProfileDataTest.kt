package id.homebase.core.ui.screens.card

import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.profile.ProfileEditViewModel
import id.homebase.core.ui.screens.profile.ProfileField
import id.homebase.core.ui.screens.profile.visiblePhoto
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
        attributes: List<ProfileAttribute>,
        tier: ProfileVisibility = ProfileVisibility.ANONYMOUS,
        photoSrc: String? = null,
        headerSrc: String? = null,
        tagLine: String? = null,
    ) = buildCardPayload(odinId, attributes, tier, CardDesign.BOARD, photoSrc, headerSrc, tagLine)

    /** One stored record per attribute type that has any of [values], at [tier]. */
    private fun records(tier: ProfileVisibility, vararg values: Pair<ProfileField, String>): List<ProfileAttribute> {
        val byField = mapOf(*values)
        return ProfileEditViewModel.TYPE_FIELDS.mapNotNull { (type, fields) ->
            val data = fields.mapNotNull { (field, key) -> byField[field]?.let { key to JsonPrimitive(it) } }.toMap()
            if (data.isEmpty()) null else record(type, tier, data)
        }
    }

    private fun record(
        type: String,
        tier: ProfileVisibility,
        data: Map<String, JsonPrimitive>,
        acl: AccessControlList = AccessControlList(requiredSecurityGroup = tier.wireValue),
        priority: Int = 0,
    ) = ProfileAttribute(
        id = Uuid.random(),
        type = type,
        versionTag = Uuid.random(),
        visibility = tier,
        data = JsonObject(data),
        acl = acl,
        priority = priority,
    )

    private fun bio(tier: ProfileVisibility, text: String, acl: AccessControlList = AccessControlList(tier.wireValue)) =
        record(ProfileAttributeTypes.BIO_SUMMARY, tier, mapOf(ProfileAttributeTypes.KEY_SHORT_BIO to JsonPrimitive(text)), acl)

    private fun link(
        text: String?,
        target: String?,
        tier: ProfileVisibility = ProfileVisibility.ANONYMOUS,
        priority: Int = 0,
        acl: AccessControlList = AccessControlList(tier.wireValue),
    ) = record(
        ProfileAttributeTypes.LINK,
        tier,
        listOfNotNull(
            text?.let { ProfileAttributeTypes.KEY_LINK_TEXT to JsonPrimitive(it) },
            target?.let { ProfileAttributeTypes.KEY_LINK_TARGET to JsonPrimitive(it) },
        ).toMap(),
        acl,
        priority,
    )

    private fun photo(visibility: ProfileVisibility) = record(
        ProfileAttributeTypes.PHOTO,
        visibility,
        mapOf(ProfileAttributeTypes.KEY_PROFILE_IMAGE to JsonPrimitive("prfl_pic")),
    )

    private fun socials(vararg values: Pair<ProfileField, String>) =
        payload(records(ProfileVisibility.ANONYMOUS, *values)).data.socials

    @Test
    fun publicTierIgnoresConnectedValues() {
        val attributes = records(
            ProfileVisibility.ANONYMOUS,
            ProfileField.GIVEN_NAME to "Frodo",
            ProfileField.SURNAME to "Baggins",
            ProfileField.STATUS to "Public status",
        ) + records(
            ProfileVisibility.CONNECTED,
            ProfileField.GIVEN_NAME to "Mr. Frodo",
            ProfileField.STATUS to "Vetted status",
            ProfileField.TWITTER to "frodo_vetted",
        )
        val data = payload(attributes, ProfileVisibility.ANONYMOUS).data
        assertEquals("Frodo", data.firstName)
        assertEquals("Baggins", data.surName)
        assertEquals("Frodo Baggins", data.displayName)
        assertEquals("Public status", data.headline)
        assertEquals(emptyList(), data.socials)
    }

    @Test
    fun vettedTierTakesTheConnectedRecordPerTypeAndFallsBackToPublic() {
        val attributes = records(
            ProfileVisibility.ANONYMOUS,
            ProfileField.GIVEN_NAME to "Frodo",
            ProfileField.SURNAME to "Baggins",
            ProfileField.STATUS to "Public status",
            ProfileField.INSTAGRAM to "frodo_public",
        ) + records(
            ProfileVisibility.CONNECTED,
            ProfileField.GIVEN_NAME to "Mr. Frodo",
            ProfileField.SURNAME to "   ",
            ProfileField.STATUS to "  ",
            ProfileField.TWITTER to "frodo_vetted",
        )
        val data = payload(attributes, ProfileVisibility.CONNECTED).data
        assertEquals("Mr. Frodo", data.firstName)
        assertNull(data.surName)
        assertEquals("Mr. Frodo", data.displayName)
        assertEquals("Public status", data.headline)
        assertEquals(
            listOf(CardSocial("twitter", "frodo_vetted"), CardSocial("instagram", "frodo_public")),
            data.socials,
        )
    }

    @Test
    fun ownerOnlyAndCircleOnlyRecordsNeverReachTheVettedCard() {
        val attributes = records(ProfileVisibility.ANONYMOUS, ProfileField.STATUS to "Public status") +
            records(ProfileVisibility.OWNER, ProfileField.GIVEN_NAME to "Private", ProfileField.STATUS to "Owner only") +
            record(
                ProfileAttributeTypes.TWITTER,
                ProfileVisibility.CONNECTED,
                mapOf(ProfileAttributeTypes.KEY_TWITTER to JsonPrimitive("inner_circle")),
                AccessControlList("connected", circleIdList = listOf("0f2c1a8e5b3d4e6f9a1b2c3d4e5f6a7b")),
            )
        val data = payload(attributes, ProfileVisibility.CONNECTED).data
        assertNull(data.firstName)
        assertEquals("Public status", data.headline)
        assertEquals(emptyList(), data.socials)
    }

    @Test
    fun photoTierFallsBackToPublicOnlyAboveThePublicTier() {
        val public = photo(ProfileVisibility.ANONYMOUS)
        val vetted = photo(ProfileVisibility.CONNECTED)

        val both = listOf(public, vetted)
        assertSame(public, both.visiblePhoto(ProfileVisibility.ANONYMOUS))
        assertSame(vetted, both.visiblePhoto(ProfileVisibility.CONNECTED))

        val publicOnly = listOf(public)
        assertSame(public, publicOnly.visiblePhoto(ProfileVisibility.CONNECTED))

        val vettedOnly = listOf(vetted)
        assertNull(vettedOnly.visiblePhoto(ProfileVisibility.ANONYMOUS))
        assertSame(vetted, vettedOnly.visiblePhoto(ProfileVisibility.CONNECTED))

        assertNull(listOf(photo(ProfileVisibility.OWNER)).visiblePhoto(ProfileVisibility.CONNECTED))
    }

    @Test
    fun imagesPassThroughAndBlankImagesDropOut() {
        val state = emptyList<ProfileAttribute>()
        val data = payload(state, photoSrc = "data:image/jpeg;base64,AAAA", headerSrc = "data:image/jpeg;base64,BBBB").data
        assertEquals(CardImage("data:image/jpeg;base64,AAAA"), data.photo)
        assertEquals(CardImage("data:image/jpeg;base64,BBBB"), data.header)

        val blank = payload(state, photoSrc = "", headerSrc = "  ").data
        assertNull(blank.photo)
        assertNull(blank.header)
    }

    @Test
    fun blankFieldsDropOutAndValuesAreTrimmed() {
        val state = records(
            ProfileVisibility.ANONYMOUS,
            ProfileField.GIVEN_NAME to "  Frodo ",
            ProfileField.SURNAME to "",
            ProfileField.STATUS to " \n ",
            ProfileField.TWITTER to "   ",
            ProfileField.LINKEDIN to "",
        ) + bio(ProfileVisibility.ANONYMOUS, "  ") + link("  ", "  ")
        val data = payload(state).data
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
        val data = payload(emptyList(), ProfileVisibility.CONNECTED).data
        assertEquals(CardData(odinId = odinId), data)
    }

    @Test
    fun bioIsTheSummaryEachTierCanRead() {
        val publicBio = bio(ProfileVisibility.ANONYMOUS, " Ring-bearer. ")
        val vettedBio = bio(ProfileVisibility.CONNECTED, "Ring-bearer, and fond of mushrooms.")
        val ownerBio = bio(ProfileVisibility.OWNER, "Still has the ring.")
        val circleBio = bio(
            ProfileVisibility.CONNECTED,
            "Fellowship only.",
            AccessControlList("connected", circleIdList = listOf("0f2c1a8e5b3d4e6f9a1b2c3d4e5f6a7b")),
        )
        val all = listOf(publicBio, vettedBio, ownerBio, circleBio)

        assertEquals("Ring-bearer.", payload(all, ProfileVisibility.ANONYMOUS).data.bio)
        assertEquals("Ring-bearer, and fond of mushrooms.", payload(all, ProfileVisibility.CONNECTED).data.bio)
        assertEquals("Ring-bearer.", payload(listOf(publicBio, ownerBio), ProfileVisibility.CONNECTED).data.bio)
        assertNull(payload(listOf(vettedBio, ownerBio), ProfileVisibility.ANONYMOUS).data.bio)
    }

    @Test
    fun linksKeepOnlyHttpAndHttpsWithTheTargetAsFallbackText() {
        val links = listOf(
            link("Blog", "https://frodo.dotyou.cloud/posts", priority = 1),
            link("Script", "javascript:alert(1)", priority = 2),
            link("Script caps", "JavaScript://%0aalert(1)", priority = 3),
            link("Inline", "data:text/html,<script>alert(1)</script>", priority = 4),
            link("Files", "ftp://files.shire.me", priority = 5),
            link("Mail", "mailto:frodo@shire.me", priority = 6),
            link("Relative", "/posts", priority = 7),
            link("No host", "https://", priority = 8),
            link("Spaced", "https://exa mple.com", priority = 9),
            link("Missing", null, priority = 10),
            link("Old site", " HTTP://shire.me/frodo ", priority = 11),
            link("  ", "https://example.com/there-and-back", priority = 12),
            link(null, "https://example.com/no-text", priority = 13),
        )
        assertEquals(
            listOf(
                CardLink(id = "1", text = "Blog", target = "https://frodo.dotyou.cloud/posts"),
                CardLink(id = "2", text = "Old site", target = "HTTP://shire.me/frodo"),
                CardLink(id = "3", text = "https://example.com/there-and-back", target = "https://example.com/there-and-back"),
                CardLink(id = "4", text = "https://example.com/no-text", target = "https://example.com/no-text"),
            ),
            payload(links).data.links,
        )
    }

    @Test
    fun eachTierGetsEveryLinkItCanReadInPriorityOrder() {
        val circle = AccessControlList("connected", circleIdList = listOf("0f2c1a8e5b3d4e6f9a1b2c3d4e5f6a7b"))
        val attributes = listOf(
            link("Vetted only", "https://shire.me/vetted", ProfileVisibility.CONNECTED, priority = 20),
            link("Owner only", "https://shire.me/owner", ProfileVisibility.OWNER, priority = 5),
            link("Fellowship", "https://shire.me/fellowship", ProfileVisibility.CONNECTED, priority = 1, acl = circle),
            link("Blog", "https://shire.me/blog", priority = 30),
            link("Shop", "https://shire.me/shop", priority = 10),
            link("Vetted tie", "https://shire.me/tie-vetted", ProfileVisibility.CONNECTED, priority = 40),
            link("Public tie", "https://shire.me/tie-public", priority = 40),
        ) + records(ProfileVisibility.ANONYMOUS, ProfileField.TWITTER to "frodo")

        assertEquals(
            listOf("Shop", "Blog", "Public tie"),
            payload(attributes, ProfileVisibility.ANONYMOUS).data.links.map { it.text },
        )
        assertEquals(
            listOf("Shop", "Vetted only", "Blog", "Vetted tie", "Public tie"),
            payload(attributes, ProfileVisibility.CONNECTED).data.links.map { it.text },
        )
        assertEquals(listOf("1", "2", "3", "4", "5"), payload(attributes, ProfileVisibility.CONNECTED).data.links.map { it.id })
    }

    @Test
    fun aLinkKeptAtBothTiersShowsOnce() {
        val attributes = listOf(
            link("Blog", "https://shire.me/blog", ProfileVisibility.CONNECTED, priority = 1),
            link("Blog", "https://shire.me/blog", priority = 2),
        )
        assertEquals(
            listOf(CardLink(id = "1", text = "Blog", target = "https://shire.me/blog")),
            payload(attributes, ProfileVisibility.CONNECTED).data.links,
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
        val state = records(
            ProfileVisibility.ANONYMOUS,
            ProfileField.GIVEN_NAME to "Frodo",
            ProfileField.SURNAME to "Baggins",
            ProfileField.STATUS to "Bag End, the Shire",
            ProfileField.INSTAGRAM to "@frodo",
        ) + bio(ProfileVisibility.ANONYMOUS, "Ring-bearer.") + link("Blog", "https://frodo.dotyou.cloud/posts")
        val post = CardPost(id = "f1", href = "https://frodo.dotyou.cloud/posts/public-posts/p1", date = 1)
        val built = buildCardPayload(
            odinId, state, ProfileVisibility.ANONYMOUS, CardDesign.POSTER,
            photoSrc = "data:image/jpeg;base64,AAAA", headerSrc = "data:image/jpeg;base64,BBBB", tagLine = null,
            posts = listOf(post),
        )
        val json = cardJson.encodeToString(CardPayload.serializer(), built)
        val root = cardJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("design", "data"), root.keys)
        assertEquals(JsonPrimitive("poster"), root["design"])
        val data = root.getValue("data").jsonObject
        assertEquals(
            setOf(
                "odinId", "firstName", "surName", "displayName", "headline", "bio",
                "photo", "header", "links", "socials", "posts",
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
        assertEquals(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("f1"),
                    "href" to JsonPrimitive("https://frodo.dotyou.cloud/posts/public-posts/p1"),
                    "date" to JsonPrimitive(1),
                ),
            ),
            data.getValue("posts").jsonArray.single(),
        )
        assertFalse("null" in json)
    }
}
