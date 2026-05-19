package id.homebase.api.video

import android.util.Log
import org.mp4parser.IsoFile
import org.mp4parser.boxes.iso14496.part12.UserDataBox
import org.mp4parser.tools.Path
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Strips EXIF/location metadata from MP4 files without re-encoding video data.
 * Walks `moov/udta` (and per-track `moov/trak/udta`) atoms, removes any boxes
 * whose 4-char type matches a known location-metadata type, and writes a
 * cleaned copy. The mdat (video samples) is copied through byte-for-byte.
 *
 * Stripped atom types:
 *  - `©xyz` (0xA9 'xyz'): Apple/Quicktime GPS coordinates — what iOS and most
 *    Android phones write when location services are on for the camera.
 *  - `loci`: ISO 23001-12 location info box (rare on phone-captured video).
 *  - `gpsa`: GPS additional info.
 *
 * Intentionally narrow: doesn't touch `moov/meta/ilst/©loc`-style iTunes
 * metadata, doesn't touch capture timestamps, doesn't touch creation tools.
 * Extend the set above if a real-world file shows up that carries location in
 * a different atom.
 */
object Mp4LocationStripper {
    private const val TAG = "Mp4LocationStripper"

    private val LOCATION_BOX_TYPES = setOf(
        "©xyz",
        "loci",
        "gpsa",
    )

    /**
     * Reads the MP4 at [inputPath], drops any location atoms found in
     * `moov/udta` / `moov/trak/udta`, and writes the result to [outputPath].
     *
     * @return `true` if location atoms were found AND a cleaned file was
     *   written to [outputPath]; `false` if the input had no location atoms
     *   (or parsing failed), in which case [outputPath] is left untouched and
     *   the caller should keep using the original input.
     */
    fun stripTo(inputPath: String, outputPath: String): Boolean {
        val inFile = File(inputPath)
        if (!inFile.exists()) {
            Log.w(TAG, "Input file does not exist: $inputPath")
            return false
        }

        return try {
            RandomAccessFile(inFile, "r").use { raf ->
                val isoFile = IsoFile(raf.channel)
                try {
                    if (!removeLocationBoxes(isoFile)) {
                        return@use false
                    }
                    FileChannel.open(
                        File(outputPath).toPath(),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    ).use { out -> isoFile.getBox(out) }
                    true
                } finally {
                    isoFile.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to strip location from $inputPath", e)
            // Best-effort cleanup of a partial write.
            runCatching { File(outputPath).delete() }
            false
        }
    }

    private fun removeLocationBoxes(isoFile: IsoFile): Boolean {
        var stripped = false
        val udtas: List<UserDataBox> =
            Path.getPaths<UserDataBox>(isoFile, "moov/udta") +
            Path.getPaths<UserDataBox>(isoFile, "moov/trak/udta")
        for (udta in udtas) {
            val kept = udta.boxes.filterNot { it.type in LOCATION_BOX_TYPES }
            if (kept.size != udta.boxes.size) {
                udta.setBoxes(kept)
                stripped = true
            }
        }
        return stripped
    }
}
