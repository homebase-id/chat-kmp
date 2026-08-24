package id.homebase.chat.contactcard

import id.homebase.core.share.SharedContentDescriptor
import id.homebase.core.share.SharedContentType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the descriptor-driven half of the contact-share path — the one iOS takes. A shared `.vcf`
 * reaches the app as a generic `FILE` descriptor, and must come out of [SharedVCardDetector] as a
 * parsed contact so the send path can ship a contact card instead of an opaque attachment.
 * Anything else, or a vCard that won't parse, must leave the detector null so the existing
 * file/text send runs unchanged.
 *
 * Companion to androidApp's `ShareReceiverVCardIntentTest`, which pins the Intent-driven half.
 */
class SharedVCardDetectorTest {

    private val vcard = """
        BEGIN:VCARD
        VERSION:3.0
        N:Vance;Ada;;;
        FN:Ada Vance
        ORG:Homebase;Engineering
        TEL;TYPE=CELL:+14155550123
        EMAIL;TYPE=INTERNET:ada@example.com
        END:VCARD
    """.trimIndent()

    private var readCount = 0
    private val files = mutableMapOf<String, String>()

    private fun descriptor(
        contentType: SharedContentType = SharedContentType.FILE,
        text: String? = null,
        fileNames: List<String> = emptyList(),
        mimeTypes: List<String> = emptyList(),
    ) = SharedContentDescriptor(
        contentType = contentType,
        text = text,
        fileNames = fileNames,
        mimeTypes = mimeTypes,
        targetConversationId = "0198cf39-0000-7000-8000-000000000001",
    )

    private suspend fun detect(
        descriptor: SharedContentDescriptor,
        sizeOverride: Long? = null,
    ): VCardContact? = SharedVCardDetector.detect(
        descriptor = descriptor,
        fileSize = { sizeOverride ?: files[it]?.length?.toLong() ?: 0L },
        readFileText = {
            readCount++
            files.getValue(it)
        },
    )

    @Test
    fun `a vcf lands as a generic FILE descriptor and is still recognised`() = runTest {
        files["Ada Vance.vcf"] = vcard

        val contact = assertNotNull(
            detect(
                descriptor(
                    fileNames = listOf("Ada Vance.vcf"),
                    // What UTType(filenameExtension:) hands the iOS extension for a .vcf.
                    mimeTypes = listOf("text/vcard"),
                )
            ),
            "iOS delivers a shared contact as contentType=FILE with a .vcf name.",
        )

        assertEquals("Ada Vance", contact.displayName)
        assertEquals(listOf("+14155550123"), contact.phones)
        assertEquals(listOf("ada@example.com"), contact.emails)
    }

    @Test
    fun `a text-vcard mime is recognised even when the file has no vcf extension`() = runTest {
        files["contact"] = vcard

        assertNotNull(detect(descriptor(fileNames = listOf("contact"), mimeTypes = listOf("text/vcard"))))
    }

    @Test
    fun `a charset parameter on the mime type does not defeat detection`() = runTest {
        files["contact"] = vcard

        assertNotNull(
            detect(
                descriptor(
                    fileNames = listOf("contact"),
                    mimeTypes = listOf("TEXT/X-VCARD; charset=utf-8"),
                )
            )
        )
    }

    @Test
    fun `a vcf filename is recognised even when the mime type is generic`() = runTest {
        files["Ada Vance.VCF"] = vcard

        assertNotNull(
            detect(
                descriptor(
                    fileNames = listOf("Ada Vance.VCF"),
                    mimeTypes = listOf("application/octet-stream"),
                )
            ),
            "Senders that declare application/octet-stream still name the file *.vcf.",
        )
    }

    @Test
    fun `a vCard body arriving in the descriptor text is recognised`() = runTest {
        val contact = assertNotNull(detect(descriptor(contentType = SharedContentType.TEXT, text = vcard)))

        assertEquals("Ada Vance", contact.displayName)
        assertEquals(0, readCount, "A text-only share reads no file.")
    }

    @Test
    fun `a vcf shared alongside other files stays on the file path so nothing is dropped`() = runTest {
        files["photo.jpg"] = "not a card"
        files["Ada Vance.vcf"] = vcard
        files["notes.pdf"] = "%PDF-1.7"
        val mixed = descriptor(
            contentType = SharedContentType.MIXED,
            fileNames = listOf("photo.jpg", "Ada Vance.vcf", "notes.pdf"),
            mimeTypes = listOf("image/jpeg", "text/vcard", "application/pdf"),
        )

        assertNull(
            detect(mixed),
            "A contact card replaces the whole share — the co-shared files would be lost.",
        )
        assertEquals(
            listOf("photo.jpg", "Ada Vance.vcf", "notes.pdf"),
            mixed.fileNames,
            "…so all three still go out through the ordinary multi-file send.",
        )
        assertEquals(0, readCount)
    }

    @Test
    fun `a vCard body in the text of a file share does not drop the file`() = runTest {
        files["photo.jpg"] = "not a card"

        assertNull(
            detect(
                descriptor(
                    contentType = SharedContentType.MIXED,
                    text = vcard,
                    fileNames = listOf("photo.jpg"),
                    mimeTypes = listOf("image/jpeg"),
                )
            ),
            "Sending the card here would lose the photo.",
        )
    }

    @Test
    fun `a lone vcf with a caption is still a contact card`() = runTest {
        files["Ada Vance.vcf"] = vcard

        val contact = assertNotNull(
            detect(
                descriptor(
                    contentType = SharedContentType.MIXED,
                    text = "here's Ada",
                    fileNames = listOf("Ada Vance.vcf"),
                    mimeTypes = listOf("text/vcard"),
                )
            ),
            "The gate is on co-shared FILES; a caption carries nothing that can be dropped.",
        )

        assertEquals("Ada Vance", contact.displayName)
    }

    @Test
    fun `a multi-contact vcf yields the first block`() = runTest {
        files["two.vcf"] = """
            BEGIN:VCARD
            VERSION:3.0
            FN:First Person
            TEL:+14155550001
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Second Person
            TEL:+14155550002
            END:VCARD
        """.trimIndent()

        val contact = assertNotNull(detect(descriptor(fileNames = listOf("two.vcf"), mimeTypes = listOf("text/vcard"))))

        assertEquals("First Person", contact.displayName)
    }

    @Test
    fun `an ordinary file share is untouched and never read`() = runTest {
        files["report.pdf"] = "%PDF-1.7"

        assertNull(detect(descriptor(fileNames = listOf("report.pdf"), mimeTypes = listOf("application/pdf"))))
        assertEquals(0, readCount, "A non-vCard share must not be read looking for a contact.")
    }

    @Test
    fun `an ordinary text share is untouched`() = runTest {
        assertNull(
            detect(
                descriptor(
                    contentType = SharedContentType.URL,
                    text = "check out https://homebase.id",
                )
            )
        )
    }

    @Test
    fun `an oversize vcf is not read and falls back to the file send`() = runTest {
        files["huge.vcf"] = vcard

        assertNull(
            detect(
                descriptor(fileNames = listOf("huge.vcf"), mimeTypes = listOf("text/vcard")),
                sizeOverride = SharedVCardDetector.MAX_VCARD_BYTES + 1,
            )
        )
        assertEquals(0, readCount, "A mislabelled huge file must never be slurped into RAM.")
    }

    @Test
    fun `a vcf sized exactly at the cap is still read`() = runTest {
        files["edge.vcf"] = vcard

        assertNotNull(
            detect(
                descriptor(fileNames = listOf("edge.vcf"), mimeTypes = listOf("text/vcard")),
                sizeOverride = SharedVCardDetector.MAX_VCARD_BYTES,
            )
        )
    }

    @Test
    fun `an unreadable vcf falls back rather than dropping the share`() = runTest {
        // No entry in `files`, so the read lambda throws — mirrors a file swept before send.
        assertNull(detect(descriptor(fileNames = listOf("gone.vcf"), mimeTypes = listOf("text/vcard"))))
    }

    @Test
    fun `a vcf that is not actually a vCard falls back to the file send`() = runTest {
        files["fake.vcf"] = "this is not a vCard at all"

        assertNull(detect(descriptor(fileNames = listOf("fake.vcf"), mimeTypes = listOf("text/vcard"))))
    }

    @Test
    fun `a vcf whose blocks carry nothing renderable falls back to the file send`() = runTest {
        files["empty.vcf"] = "BEGIN:VCARD\nVERSION:3.0\nFN:\nTEL:\nEND:VCARD"

        assertNull(detect(descriptor(fileNames = listOf("empty.vcf"), mimeTypes = listOf("text/vcard"))))
    }

    @Test
    fun `a broken vcf does not fall through to unrelated descriptor text`() = runTest {
        files["fake.vcf"] = "this is not a vCard at all"

        assertNull(
            detect(
                descriptor(
                    contentType = SharedContentType.MIXED,
                    text = vcard,
                    fileNames = listOf("fake.vcf"),
                    mimeTypes = listOf("text/vcard"),
                )
            ),
            "The named file is the share; a caption must not be parsed in its place.",
        )
    }

    @Test
    fun `a descriptor carrying no vCard at all leaves every send path unchanged`() = runTest {
        val plain = descriptor(
            contentType = SharedContentType.IMAGE,
            text = "look at this",
            fileNames = listOf("photo.jpg"),
            mimeTypes = listOf("image/jpeg"),
        )

        assertNull(detect(plain))
        assertTrue(plain.fileNames.isNotEmpty(), "…and the file is still there to send.")
        assertFalse(plain.text.isNullOrBlank())
    }
}
