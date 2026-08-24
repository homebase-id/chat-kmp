package id.homebase.api.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the Moments "Shared with" dedupe (#1007). The reported "two Michael" was
 * assumed to be case-variant OdinIds surviving `.distinct()` — but OdinId equality
 * is already case-insensitive (AsciiDomainName lowercases on construction), so
 * `.distinct()` on a `List<OdinId>` collapses them. The genuine dup is in the
 * transfer-history rows, deduped by the raw-recipient string lowercased.
 */
class OdinIdDedupeTest {

    @Test
    fun distinctCollapsesCaseVariantOdinIds() {
        val recipients = listOf(OdinId("Michael.x"), OdinId("michael.x"), OdinId("MICHAEL.X"))
        assertEquals(1, recipients.distinct().size)
    }

    @Test
    fun distinctKeepsGenuinelyDifferentIdentities() {
        val recipients = listOf(OdinId("michael.x"), OdinId("sarah.y"), OdinId("Michael.x"))
        assertEquals(2, recipients.distinct().size)
    }

    @Test
    fun deliveryRowKeyDedupesRawRecipientStringsByCase() {
        // Transfer-history rows carry the raw recipient string, not an OdinId.
        val rawRecipients = listOf("Michael.x", "michael.x", "sarah.y")
        assertEquals(2, rawRecipients.distinctBy { it.lowercase() }.size)
    }
}
