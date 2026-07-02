package id.homebase.api.client.contacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins social-handle resolution. The critical contract is the key format: stored attribute-type ids
 * are the DASHLESS 32-hex form, while [ContactAttributeId] constants are hyphenated — [normalizeId]
 * must bridge the two.
 */
class ContactSocialTest {

    // Dashless forms of the stored keys (the way they actually arrive in ContactContent.social).
    private val twitterDashless = "54ecbdc035fd1a44d0524303cd104411"
    private val githubDashless = "9f1ea770fb88720c48861df0f277fcea"

    @Test
    fun fromId_resolvesDashlessAndHyphenatedAndIsCaseInsensitive() {
        assertEquals(ContactSocialNetwork.Twitter, ContactSocialNetwork.fromId(twitterDashless))
        assertEquals(ContactSocialNetwork.Twitter, ContactSocialNetwork.fromId(ContactAttributeId.TWITTER))
        assertEquals(
            ContactSocialNetwork.Twitter,
            ContactSocialNetwork.fromId(twitterDashless.uppercase()),
        )
    }

    @Test
    fun fromId_unknownIsNull() {
        assertNull(ContactSocialNetwork.fromId("00000000000000000000000000000000"))
    }

    @Test
    fun socialHandles_resolvesDashlessKeysInNetworkOrderDroppingBlankAndUnknown() {
        val content = ContactContent(
            social = mapOf(
                githubDashless to "octocat",          // GitHub comes AFTER Twitter in enum order
                twitterDashless to "@jack",
                "00000000000000000000000000000000" to "ignored", // unknown network -> dropped
                ContactAttributeId.DISCORD.replace("-", "") to "  ", // blank handle -> dropped
            ),
        )

        val resolved = content.socialHandles()

        // Order follows the enum (Twitter before GitHub), regardless of map insertion order.
        assertEquals(
            listOf(
                ContactSocialNetwork.Twitter to "@jack",
                ContactSocialNetwork.Github to "octocat",
            ),
            resolved,
        )
    }

    @Test
    fun socialHandles_nullMapIsEmpty() {
        assertTrue(ContactContent().socialHandles().isEmpty())
    }
}
