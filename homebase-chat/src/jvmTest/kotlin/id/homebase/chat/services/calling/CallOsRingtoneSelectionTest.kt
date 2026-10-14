package id.homebase.chat.services.calling

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallOsRingtoneSelectionTest {
    private val tones = "/System/Library/PrivateFrameworks/ToneLibrary.framework/Versions/A/Resources/Ringtones"
    private val oga = "/usr/share/sounds/freedesktop/stereo/phone-incoming-call.oga"

    private fun probe(files: Set<String> = emptySet(), commands: Set<String> = emptySet(), win: String? = null, alert: String? = null) =
        RingProbe({ it in files }, { it in commands }, { win }, { alert })

    @Test
    fun macUsesTheSystemRingtoneThenTheUsersAlertSoundThenGlass() {
        assertEquals(
            RingSource.Command(listOf("afplay", "$tones/Reflection.m4r")),
            osRingSource("Mac OS X", probe(setOf("$tones/Reflection.m4r", "/Users/a/alert.aiff"), alert = "/Users/a/alert.aiff")),
        )
        assertEquals(
            RingSource.Command(listOf("afplay", "/Users/a/alert.aiff")),
            osRingSource("Mac OS X", probe(setOf("/Users/a/alert.aiff", "/System/Library/Sounds/Glass.aiff"), alert = "/Users/a/alert.aiff")),
        )
        assertEquals(
            RingSource.Command(listOf("afplay", "/System/Library/Sounds/Glass.aiff")),
            osRingSource("Mac OS X", probe(setOf("/System/Library/Sounds/Glass.aiff"))),
        )
    }

    @Test
    fun windowsPrefersTheRegistryRingtoneThenTheMediaRingFiles() {
        assertEquals(
            RingSource.Wav(File("C:\\Windows\\Media\\Ring05.wav")),
            osRingSource("Windows 11", probe(setOf("C:\\Windows\\Media\\Ring05.wav", "C:\\Windows\\Media\\Ring01.wav"), win = "C:\\Windows\\Media\\Ring05.wav")),
        )
        assertEquals(
            RingSource.Wav(File("C:\\Windows\\Media\\Ring01.wav")),
            osRingSource("Windows 11", probe(setOf("C:\\Windows\\Media\\Ring01.wav"), win = "C:\\gone.wav")),
        )
    }

    @Test
    fun linuxAsksCanberraForTheSoundThemeEventAndFallsBackToTheOgaFile() {
        assertEquals(
            RingSource.Command(listOf("canberra-gtk-play", "-i", "phone-incoming-call")),
            osRingSource("Linux", probe(setOf(oga), setOf("canberra-gtk-play", "paplay"))),
        )
        assertEquals(
            RingSource.Command(listOf("paplay", oga)),
            osRingSource("Linux", probe(setOf(oga), setOf("paplay"))),
        )
        assertNull(osRingSource("Linux", probe(setOf(oga))))
    }

    @Test
    fun registryValueIsParsedAndItsEnvironmentVariablesExpanded() {
        val out = "\r\nHKEY_CURRENT_USER\\AppEvents\\Schemes\\Apps\\.Default\\Notification.Looping.Call\\.Current\r\n    (Default)    REG_EXPAND_SZ    %SystemRoot%\\media\\Ring03.wav\r\n"
        assertEquals("C:\\Windows\\media\\Ring03.wav", parseRegQueryValue(out) { if (it == "SystemRoot") "C:\\Windows" else null })
        assertNull(parseRegQueryValue("ERROR: The system was unable to find the specified registry key"))
    }
}
