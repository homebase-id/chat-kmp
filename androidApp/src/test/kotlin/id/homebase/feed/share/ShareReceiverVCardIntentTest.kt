package id.homebase.feed.share

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import id.homebase.core.ui.screens.contactbook.ContactCardImport
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the inbound-contact-share path end to end at the intent level: an ACTION_SEND carrying a
 * vCard — as an `EXTRA_STREAM` `.vcf` or as an `EXTRA_TEXT` body — must come out of
 * [SharedContentExtractor] + [ContactShareDetector] as a parsed contact that
 * [ContactCardImport] can turn into a contact-card descriptor and a pre-filled contact-editor
 * draft. Anything that isn't a contact, or a vCard that won't parse, must leave the detector
 * null so the existing raw-file/text send paths still run (a share is never dropped).
 *
 * Companion to [ShareReceiverActivityIntentTest], which pins the post-send deep link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareReceiverVCardIntentTest {

    private val vcard = """
        BEGIN:VCARD
        VERSION:3.0
        N:Vance;Ada;;;
        FN:Ada Vance
        ORG:Homebase;Engineering
        TEL;TYPE=CELL:+1 (415) 555-0123
        EMAIL;TYPE=INTERNET:ada@example.com
        END:VCARD
    """.trimIndent()

    private val contentResolver get() = RuntimeEnvironment.getApplication().contentResolver

    private fun tempDir(): File =
        File(RuntimeEnvironment.getApplication().cacheDir, "share_temp_${System.nanoTime()}")

    private fun extract(intent: Intent): SharedContent =
        assertNotNull(SharedContentExtractor.extract(intent, contentResolver, tempDir()))

    private fun vcfIntent(body: String = vcard, mimeType: String = "text/x-vcard"): Intent {
        val file = File.createTempFile("Ada_Vance", ".vcf").apply { writeText(body) }
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, file.toUri())
        }
    }

    private fun textIntent(body: String, mimeType: String = "text/x-vcard"): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_TEXT, body)
        }

    @Test
    fun `a vcf shared via EXTRA_STREAM becomes a contact card, not an opaque file`() {
        val intent = vcfIntent()
        val content = extract(intent)

        assertTrue(content.hasFiles, "The extractor still copies the .vcf to the temp dir.")

        val contact = assertNotNull(
            ContactShareDetector.detect(intent.type, content),
            "A text/x-vcard EXTRA_STREAM share must be recognised as a contact.",
        )
        val descriptor = assertNotNull(ContactCardImport.toDescriptor(contact))

        assertEquals("Ada Vance", descriptor.displayName)
        assertEquals("Homebase", descriptor.organization)
        assertEquals(listOf("+14155550123"), descriptor.phones)
        assertEquals(listOf("ada@example.com"), descriptor.emails)
    }

    @Test
    fun `a vcf is recognised from its filename even when the mime type is generic`() {
        // Built directly rather than through the extractor: a Robolectric file:// URI has no
        // OpenableColumns row, so the extractor can't resolve a display name here. A real
        // content:// share does, which is exactly the case this fallback covers.
        val file = File.createTempFile("Ada_Vance", ".vcf").apply { writeText(vcard) }
        val content = SharedContent(
            files = listOf(SharedFile(file.absolutePath, "application/octet-stream", "Ada Vance.vcf")),
        )

        assertNotNull(
            ContactShareDetector.detect("application/octet-stream", content),
            "Senders that declare application/octet-stream still name the file *.vcf.",
        )
    }

    @Test
    fun `a vCard body shared via EXTRA_TEXT becomes a contact card`() {
        val intent = textIntent(vcard)
        val content = extract(intent)

        assertFalse(content.hasFiles)
        val contact = assertNotNull(ContactShareDetector.detect(intent.type, content))

        assertEquals("Ada Vance", ContactCardImport.toDescriptor(contact)?.displayName)
    }

    @Test
    fun `a vCard body in a text-plain share is still recognised`() {
        val intent = textIntent(vcard, mimeType = "text/plain")
        val content = extract(intent)

        assertNotNull(
            ContactShareDetector.detect(intent.type, content),
            "Some senders declare text/plain and put the BEGIN:VCARD body in EXTRA_TEXT.",
        )
    }

    @Test
    fun `the contact-editor prefill carries the parsed fields`() {
        val intent = vcfIntent()
        val contact = assertNotNull(ContactShareDetector.detect(intent.type, extract(intent)))

        val draft = ContactCardImport.toDraft(contact)

        assertEquals("Ada", draft.givenName)
        assertEquals("Vance", draft.surname)
        assertEquals("+14155550123", draft.phone)
        assertEquals("ada@example.com", draft.email)
        assertTrue(draft.isSavable)
    }

    @Test
    fun `a legacy non-E164 number seeds the editor flagged, gating Save`() {
        val intent = vcfIntent(
            """
            BEGIN:VCARD
            VERSION:2.1
            FN:Legacy Larry
            TEL;HOME:0207 946 0018
            END:VCARD
            """.trimIndent()
        )
        val contact = assertNotNull(ContactShareDetector.detect(intent.type, extract(intent)))

        val draft = ContactCardImport.toDraft(contact)

        assertEquals("02079460018", draft.phone, "Shown, not dropped.")
        assertFalse(draft.phoneValid)
        assertFalse(draft.isSavable, "Save stays disabled until the user fixes the number.")
    }

    @Test
    fun `a multi-contact vcf sends the first block without crashing`() {
        val intent = vcfIntent(
            """
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
        )

        val contact = assertNotNull(ContactShareDetector.detect(intent.type, extract(intent)))

        assertEquals("First Person", contact.displayName)
    }

    @Test
    fun `an unparseable vcf falls back to the raw file rather than dropping the share`() {
        val intent = vcfIntent("this is not a vCard at all")
        val content = extract(intent)

        assertNull(
            ContactShareDetector.detect(intent.type, content),
            "No contact means the ordinary file send path runs — the share must not be dropped.",
        )
        assertTrue(content.hasFiles, "…and the raw file is still there to send.")
    }

    @Test
    fun `a vcf whose blocks carry nothing renderable falls back to the raw file`() {
        val intent = vcfIntent("BEGIN:VCARD\nVERSION:3.0\nFN:\nTEL:\nEND:VCARD")

        assertNull(ContactShareDetector.detect(intent.type, extract(intent)))
    }

    @Test
    fun `an ordinary image share is untouched by contact detection`() {
        val file = File.createTempFile("photo", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, file.toUri())
        }
        val content = extract(intent)

        assertNull(ContactShareDetector.detect(intent.type, content))
        assertTrue(content.hasFiles)
    }

    @Test
    fun `an ordinary text share is untouched by contact detection`() {
        val intent = textIntent("check out https://homebase.id", mimeType = "text/plain")

        assertNull(ContactShareDetector.detect(intent.type, extract(intent)))
    }
}
