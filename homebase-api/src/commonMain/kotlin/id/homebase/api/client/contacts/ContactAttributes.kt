package id.homebase.api.client.contacts

/**
 * Well-known contact attribute-type ids. Each is `toGuidId(name)` (= md5 of the attribute name) on
 * the canonical attribute name, hyphenated lowercase — the same ids the server/odin-js use. They key
 * the entries in [ContactContent.social] (and identify the typed fields, kept here for reference even
 * though those already have first-class [ContactContent] properties).
 *
 * The comment after each id is the string fed to `toGuidId` to derive it.
 */
object ContactAttributeId {
    // -- Core fields (also modeled as typed ContactContent properties) -------------------------------
    const val NAME = "b068931c-c450-442b-63f5-b3d276ea4297"          // toGuidId("name")
    const val NICKNAME = "e8067417-0aae-0390-9a55-625e9cc9cf97"      // toGuidId("nickname")
    const val PHOTO = "5ae0c1c8-a526-0bc7-b664-8f6fbd115c35"         // toGuidId("photo")
    const val ADDRESS = "d5189de0-2792-2f81-0059-51e6efe0efd5"       // toGuidId("location")
    const val BIRTHDAY = "cf673f7e-e888-28c9-fb8f-6acf2cb08403"      // toGuidId("birthday")
    const val PHONE_NUMBER = "c5754f96-3780-6a28-30ca-2a957c2ac198"  // toGuidId("phonenumber")
    const val EMAIL = "0c83f57c-786a-0b4a-39ef-ab23731c7ebc"         // toGuidId("email")
    const val STATUS = "9acb4454-9b41-5636-97bb-490144ec6258"        // toGuidId("status")
    const val LINK = "2a304a13-4845-6ccd-2234-cd71a81bd338"          // toGuidId("link")
    const val SHORT_BIO = "2cd30a58-568d-c333-2379-44481aeb9ff1"     // toGuidId("short_bio")

    // -- Social --------------------------------------------------------------------------------------
    const val HOMEBASE_IDENTITY = "0eb220c0-9268-57bd-3e31-4a0b9374e1ff" // toGuidId("dot_you_identity")
    const val TWITTER = "54ecbdc0-35fd-1a44-d052-4303cd104411"       // toGuidId("twitter_username")
    const val FACEBOOK = "ccda59a7-03e9-4acc-daab-95b58f7c20b6"      // toGuidId("facebook_username")
    const val INSTAGRAM = "345fef7b-ada5-b100-001e-4c78111c86de"     // toGuidId("instagram_username")
    const val TIKTOK = "d58890b2-f156-a0b9-413b-388773b1b0a7"        // toGuidId("tiktok_username")
    const val LINKEDIN = "a050c5ee-4b51-39b7-30cd-7eb44e7db69a"      // toGuidId("linkedin_username")
    const val YOUTUBE = "90de1008-ca7d-a7a6-272b-2a3235c66989"       // toGuidId("youtube_username")
    const val DISCORD = "967c88ca-98b3-50eb-126c-199dd28f49cb"       // toGuidId("discord_username")
    const val SNAPCHAT = "6d65f3ba-48fc-06ff-edce-f170133577f0"      // toGuidId("snapchat_username")
    const val GITHUB = "9f1ea770-fb88-720c-4886-1df0f277fcea"        // toGuidId("github_username")
    const val STACK_OVERFLOW = "6b801187-7a10-443d-0d41-2dcfad398d06" // toGuidId("stackoverflow_username")

    // -- Games ---------------------------------------------------------------------------------------
    const val EPIC = "138ce2df-9047-e6f0-4080-a0c870de5bac"          // toGuidId("epic_username")
    const val RIOT = "5c603ef7-e053-d069-10f8-c74618e7ab43"          // toGuidId("riot_username")
    const val STEAM = "e4f27af4-a80d-ff11-caac-432e4c97d79a"         // toGuidId("steam_username")
    const val MINECRAFT = "f37a742d-738e-ea92-3a7c-793dc72f8064"     // toGuidId("minecraft_username")
}

/**
 * A known social/gaming network that a contact can carry a handle for, identified by its
 * attribute-type id ([attributeId]) — the key under which the bare handle is stored in
 * [ContactContent.social]. [label] is the brand name for display (not a localized string).
 *
 * Resolve a raw social-map key with [fromId]; unknown keys (networks we don't model yet) return null
 * so callers can skip them. Order here is the order networks should render in.
 */
enum class ContactSocialNetwork(val attributeId: String, val label: String) {
    HomebaseIdentity(normalizeId(ContactAttributeId.HOMEBASE_IDENTITY), "Homebase"),
    Twitter(normalizeId(ContactAttributeId.TWITTER), "Twitter"),
    Facebook(normalizeId(ContactAttributeId.FACEBOOK), "Facebook"),
    Instagram(normalizeId(ContactAttributeId.INSTAGRAM), "Instagram"),
    Tiktok(normalizeId(ContactAttributeId.TIKTOK), "TikTok"),
    LinkedIn(normalizeId(ContactAttributeId.LINKEDIN), "LinkedIn"),
    Youtube(normalizeId(ContactAttributeId.YOUTUBE), "YouTube"),
    Discord(normalizeId(ContactAttributeId.DISCORD), "Discord"),
    Snapchat(normalizeId(ContactAttributeId.SNAPCHAT), "Snapchat"),
    Github(normalizeId(ContactAttributeId.GITHUB), "GitHub"),
    StackOverflow(normalizeId(ContactAttributeId.STACK_OVERFLOW), "Stack Overflow"),
    Epic(normalizeId(ContactAttributeId.EPIC), "Epic Games"),
    Riot(normalizeId(ContactAttributeId.RIOT), "Riot"),
    Steam(normalizeId(ContactAttributeId.STEAM), "Steam"),
    Minecraft(normalizeId(ContactAttributeId.MINECRAFT), "Minecraft"),
    ;

    companion object {
        private val byId = entries.associateBy { normalizeId(it.attributeId) }

        /** Resolve a [ContactContent.social] key (attribute-type id) to a known network, or null. */
        fun fromId(attributeId: String): ContactSocialNetwork? = byId[normalizeId(attributeId)]
    }
}

/**
 * Canonicalizes an attribute-type id for comparison: lowercased, dashes stripped. Stored keys are
 * the dashless 32-hex form (e.g. `d5189de027922f81005951e6efe0efd5`) while the [ContactAttributeId]
 * constants are hyphenated, so both sides are normalized before lookup.
 */
internal fun normalizeId(id: String): String = id.lowercase().replace("-", "")

/**
 * The contact's social handles resolved to known networks and rendered in [ContactSocialNetwork]
 * order. Each pair is the network and its bare handle (blank handles and unknown networks dropped).
 */
fun ContactContent.socialHandles(): List<Pair<ContactSocialNetwork, String>> {
    val map = social ?: return emptyList()
    val byKey = map.entries.associate { (k, v) -> normalizeId(k) to v }
    return ContactSocialNetwork.entries.mapNotNull { network ->
        val handle = byKey[normalizeId(network.attributeId)]?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        network to handle
    }
}
