package id.homebase.api.client.profile

/**
 * The owner's editable **standard-profile** attribute types and their `data` keys.
 *
 * Each profile attribute is a file on the owner's ProfileDrive (`fileType = 77`). The attribute's
 * `type` is one of the no-dash GUIDs below; the server validates `type` on write but does NOT
 * validate the `data` keys — a misspelled key still saves (200) but never surfaces, so the keys
 * here must match the server's expectation exactly (note the deliberate `birtday_date` typo).
 *
 * Type GUIDs are stored **no-dash** because that is the shape the V2 `/profile/attributes` write
 * endpoint accepts and the shape we normalize drive-read types to (see
 * [ProfileAttribute.normalizeType]).
 */
object ProfileAttributeTypes {

    // --- type ids (no-dash GUIDs) ---
    const val NAME = "b068931cc450442b63f5b3d276ea4297"
    const val NICKNAME = "e80674170aae03909a55625e9cc9cf97"
    const val STATUS = "9acb44549b41563697bb490144ec6258"
    const val BIRTHDAY = "cf673f7ee88828c9fb8f6acf2cb08403"
    const val EMAIL = "0c83f57c786a0b4a39efab23731c7ebc"
    const val PHONE = "c5754f9637806a2830ca2a957c2ac198"
    const val ADDRESS = "d5189de027922f81005951e6efe0efd5"
    const val TWITTER = "54ecbdc035fd1a44d0524303cd104411"
    const val FACEBOOK = "ccda59a703e94accdaab95b58f7c20b6"
    const val INSTAGRAM = "345fef7bada5b100001e4c78111c86de"
    const val TIKTOK = "d58890b2f156a0b9413b388773b1b0a7"
    const val LINKEDIN = "a050c5ee4b5139b730cd7eb44e7db69a"
    /** The owner's profile photo — written via the dedicated `/profile/attributes/photo` endpoint,
     *  not [ProfileProvider.saveAttribute]. Multiple photo attributes can coexist (one per
     *  [ProfileVisibility] tier); see [ProfileRepository.uploadPhoto]. */
    const val PHOTO = "5ae0c1c8a5260bc7b6648f6fbd115c35"

    // --- data keys: Name ---
    const val KEY_GIVEN_NAME = "givenName"
    const val KEY_SURNAME = "surname"
    const val KEY_ADDITIONAL_NAME = "additionalName"
    /** Server-derived; never sent on edit so the server recomputes it from the name parts. */
    const val KEY_DISPLAY_NAME = "displayName"
    const val KEY_EXPLICIT_DISPLAY_NAME = "explicitDisplayName"

    // --- data keys: Nickname / Status ---
    const val KEY_NICKNAME = "nickName"
    const val KEY_STATUS = "status"

    // --- data keys: Birthday (note the server's misspelling) ---
    const val KEY_BIRTHDAY = "birtday_date"

    // --- data keys: Email / Phone (each also carries a free-text label) ---
    const val KEY_EMAIL = "email"
    const val KEY_PHONE = "phone_number"
    const val KEY_LABEL = "label"

    // --- data keys: Address ---
    const val KEY_ADDRESS1 = "address1"
    const val KEY_ADDRESS2 = "address2"
    const val KEY_POSTCODE = "postcode"
    const val KEY_CITY = "city"
    const val KEY_COUNTRY = "country"

    // --- data keys: Socials (the key is the network name) ---
    const val KEY_TWITTER = "twitter"
    const val KEY_FACEBOOK = "facebook"
    const val KEY_INSTAGRAM = "instagram"
    const val KEY_TIKTOK = "tiktok"
    const val KEY_LINKEDIN = "linkedin"

    /** Data key on a [PHOTO] attribute holding the payload key to fetch the image bytes from —
     *  server-set, never sent on write (see the `/profile/attributes/photo` endpoint docs). */
    const val KEY_PROFILE_IMAGE = "profileImageKey"

    /**
     * Default [ProfileVisibility] for a brand-new attribute of [type] (one the owner has not set
     * before). Identity-public fields (name, nickname, status, social handles) default to
     * [ProfileVisibility.ANONYMOUS] so they show on the public profile card like the existing
     * name/status attributes; personal-contact fields (email, phone, birthday, address) default to
     * [ProfileVisibility.OWNER] so a freshly-typed phone number is never silently world-visible.
     *
     * Only used when CREATING; editing an existing attribute always preserves its stored visibility.
     */
    fun defaultVisibilityFor(type: String): ProfileVisibility = when (type) {
        NAME, NICKNAME, STATUS, TWITTER, FACEBOOK, INSTAGRAM, TIKTOK, LINKEDIN ->
            ProfileVisibility.ANONYMOUS

        else -> ProfileVisibility.OWNER
    }
}

/**
 * Who may read a profile attribute. Serialized to the `visibility` string the V2 write endpoint
 * expects, and parsed back from the file's `requiredSecurityGroup` on read.
 */
enum class ProfileVisibility(val wireValue: String) {
    ANONYMOUS("anonymous"),
    AUTHENTICATED("authenticated"),
    CONNECTED("connected"),
    OWNER("owner");

    /** The `/profile/attributes/photo` endpoint's `visibility` field is PascalCase
     *  ("Anonymous"/"Authenticated"/"Connected"/"Owner"), unlike [wireValue] used by the generic
     *  attribute endpoint — see [ProfileRepository.uploadPhoto]. */
    val photoWireValue: String get() = wireValue.replaceFirstChar { it.uppercaseChar() }

    companion object {
        /** Lenient parse from a stored security-group string; unknown/absent → [OWNER] (most private). */
        fun fromWire(value: String?): ProfileVisibility =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: OWNER
    }
}
