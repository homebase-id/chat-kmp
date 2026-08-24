package id.homebase.core.ui.screens.contactbook

import id.homebase.chat.contactcard.SharedVCardDetector
import id.homebase.chat.contactcard.VCardDescriptorFactory
import id.homebase.core.share.SharedContentDescriptor
import id.homebase.core.share.SharedContentType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Closes the loop the module boundary splits: chat's [SharedVCardDetector] handed to the real
 * [VCardDescriptorFactory] this module binds in Koin. Proves that what the descriptor share path
 * sends is normalized the same way the Android path normalizes it — E.164 phones via
 * [ContactFieldValidation] — rather than a verbatim copy of the vCard.
 */
class SharedVCardDescriptorPathTest {

    private val factory = VCardDescriptorFactory(ContactCardImport::toDescriptor)

    private val files = mutableMapOf<String, String>()

    private suspend fun sentDescriptor(descriptor: SharedContentDescriptor) =
        SharedVCardDetector.detect(
            descriptor = descriptor,
            fileSize = { files[it]?.length?.toLong() ?: 0L },
            readFileText = { files.getValue(it) },
        )?.let { factory.toDescriptor(it) }

    private fun fileShare(fileName: String, mimeType: String) = SharedContentDescriptor(
        contentType = SharedContentType.FILE,
        fileNames = listOf(fileName),
        mimeTypes = listOf(mimeType),
        targetConversationId = "0198cf39-0000-7000-8000-000000000001",
    )

    @Test
    fun `a shared vcf becomes a contact card with E164 phones`() = runTest {
        files["Ada Vance.vcf"] = """
            BEGIN:VCARD
            VERSION:3.0
            N:Vance;Ada;;;
            FN:Ada Vance
            ORG:Homebase;Engineering
            TEL;TYPE=CELL:+1 (415) 555-0123
            EMAIL;TYPE=INTERNET:ada@example.com
            END:VCARD
        """.trimIndent()

        val card = assertNotNull(sentDescriptor(fileShare("Ada Vance.vcf", "text/vcard")))

        assertEquals("Ada Vance", card.displayName)
        assertEquals("Homebase", card.organization)
        assertEquals(listOf("+14155550123"), card.phones)
        assertEquals(listOf("ada@example.com"), card.emails)
    }

    @Test
    fun `a card the importer rejects falls back to the file send`() = runTest {
        // Parses as a vCard (ORG only), but carries no name, phone or email — isValid() fails,
        // so the share must go out as the raw .vcf rather than as a blank bubble.
        files["org.vcf"] = "BEGIN:VCARD\nVERSION:3.0\nORG:Homebase\nEND:VCARD"

        assertNull(sentDescriptor(fileShare("org.vcf", "text/vcard")))
    }
}
