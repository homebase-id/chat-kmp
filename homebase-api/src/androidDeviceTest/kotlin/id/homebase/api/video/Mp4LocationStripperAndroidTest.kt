package id.homebase.api.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mp4parser.BasicContainer
import org.mp4parser.IsoFile
import org.mp4parser.boxes.iso14496.part12.FileTypeBox
import org.mp4parser.boxes.iso14496.part12.MovieBox
import org.mp4parser.boxes.iso14496.part12.UserDataBox
import org.mp4parser.support.AbstractBox

/**
 * Round-trip tests for [Mp4LocationStripper]. Constructs minimal in-memory MP4
 * structures via mp4parser (a `moov/udta` containing a `©xyz` GPS atom plus a
 * harmless `titl` atom), writes them out, runs the stripper, and re-parses to
 * verify the GPS atom is gone while unrelated atoms survive.
 *
 * No `@Ignore` — this test has no environment requirements beyond a booted
 * Android device/emulator. CI doesn't auto-run `connectedAndroidDeviceTest`,
 * so the test is dormant on PR builds; anyone running it locally with
 *
 *   ./gradlew homebase-api:connectedAndroidDeviceTest
 *
 * gets results.
 */
@RunWith(AndroidJUnit4::class)
class Mp4LocationStripperAndroidTest {

    private fun cacheDir(): File =
        InstrumentationRegistry.getInstrumentation().context.cacheDir

    @Test
    fun stripTo_removesLocationAtom_andLeavesOtherBoxesIntact() {
        val input = File(cacheDir(), "stripper_input_with_loc.mp4").apply { delete() }
        val output = File(cacheDir(), "stripper_output_stripped.mp4").apply { delete() }
        writeMinimalMp4(input, includeLocation = true)
        try {
            // Sanity: fixture really does contain both boxes before stripping.
            assertEquals(
                setOf("©xyz", "titl"),
                udtaChildTypes(input).toSet(),
                "Fixture must seed both ©xyz and titl before stripping",
            )

            val didStrip = Mp4LocationStripper.stripTo(input.absolutePath, output.absolutePath)
            assertTrue(didStrip, "stripTo must return true when ©xyz is present")
            assertTrue(output.exists(), "Output file must be written when stripping occurred")

            val keptTypes = udtaChildTypes(output)
            assertFalse(keptTypes.contains("©xyz"), "©xyz must be stripped, got $keptTypes")
            assertTrue(keptTypes.contains("titl"), "Unrelated atoms must survive, got $keptTypes")
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun stripTo_returnsFalse_andLeavesOutputUntouched_whenNoLocationAtoms() {
        val input = File(cacheDir(), "stripper_input_clean.mp4").apply { delete() }
        val output = File(cacheDir(), "stripper_output_clean.mp4").apply { delete() }
        writeMinimalMp4(input, includeLocation = false)
        try {
            val didStrip = Mp4LocationStripper.stripTo(input.absolutePath, output.absolutePath)
            assertFalse(didStrip, "stripTo must return false when input is clean")
            assertFalse(output.exists(), "Output file must not be written when nothing to strip")
        } finally {
            input.delete()
            output.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test fixture construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds an MP4 with shape:
     *   ftyp
     *   moov
     *     udta
     *       [©xyz?]   ← only when [includeLocation] is true
     *       titl
     *
     * No mdat is needed — the stripper only reads the moov tree.
     */
    private fun writeMinimalMp4(target: File, includeLocation: Boolean) {
        // mp4parser 1.9.39 doesn't expose a no-arg IsoFile ctor; use a
        // BasicContainer to assemble boxes and writeContainer() to serialise.
        // The on-disk shape is identical (an MP4 is just a sequence of boxes).
        val root = BasicContainer()
        root.addBox(FileTypeBox("isom", 512, mutableListOf("isom", "iso2", "mp41")))
        val moov = MovieBox()
        val udta = UserDataBox()
        if (includeLocation) udta.addBox(buildIso6709Box("©xyz", GPS_PAYLOAD))
        udta.addBox(buildIso6709Box("titl", TITLE_PAYLOAD))
        moov.addBox(udta)
        root.addBox(moov)

        FileChannel.open(
            target.toPath(),
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { root.writeContainer(it) }
    }

    /**
     * Apple/Quicktime metadata-text box shape:
     *   [u16 string length][u16 language packet][bytes...]
     */
    private fun buildIso6709Box(boxType: String, payload: ByteArray): AbstractBox =
        object : AbstractBox(boxType) {
            override fun getContentSize(): Long = (2 + 2 + payload.size).toLong()
            override fun _parseDetails(content: ByteBuffer) { /* never round-tripped */ }
            override fun getContent(byteBuffer: ByteBuffer) {
                byteBuffer.putShort(payload.size.toShort())
                byteBuffer.putShort(0x55C4.toShort())  // Apple "und" language packet
                byteBuffer.put(payload)
            }
        }

    private fun udtaChildTypes(file: File): List<String> {
        val isoFile = IsoFile(file.absolutePath)
        return try {
            val moov = isoFile.boxes.filterIsInstance<MovieBox>().first()
            val udta = moov.boxes.filterIsInstance<UserDataBox>().first()
            udta.boxes.map { it.type }
        } finally {
            isoFile.close()
        }
    }

    private companion object {
        // ISO 6709 short-form GPS string — Mount Everest summit, a deliberately
        // recognisable value if anyone ever inspects test output.
        private val GPS_PAYLOAD = "+27.5916+086.5640+8850/".toByteArray(Charsets.UTF_8)
        private val TITLE_PAYLOAD = "homebase test title".toByteArray(Charsets.UTF_8)
    }
}
