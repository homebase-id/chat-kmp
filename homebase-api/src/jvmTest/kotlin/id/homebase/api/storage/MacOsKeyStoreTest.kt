package id.homebase.api.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacOsKeyStoreTest {
    @Test
    fun `status mapping keeps denial, cancel and a locked keychain unreachable`() {
        assertEquals(OsKeyRead.Found("v"), macStatusToRead(0, "v"))
        assertEquals(OsKeyRead.Absent, macStatusToRead(-25300, null))
        listOf(-128, -25293, -25308, -25291, -1).forEach { assertIs<OsKeyRead.Unreachable>(macStatusToRead(it, null), "status $it") }
        assertIs<OsKeyRead.Unreachable>(macStatusToRead(0, null))
    }

    @Test
    fun `a duplicate add falls through to update`() {
        assertTrue(macAddNeedsUpdate(-25299))
        assertFalse(macAddNeedsUpdate(-25308))
    }

    @Test
    fun `a key envelope detects every garbling`() {
        val key = ByteArray(32) { it.toByte() }
        val text = encodeKeyEnvelope(key)

        assertNotNull(decodeKeyEnvelope(text))
        assertNull(decodeKeyEnvelope("garbage"))
        assertNull(decodeKeyEnvelope(text.dropLast(1) + "0"))
        assertNull(decodeKeyEnvelope(encodeKeyEnvelope(ByteArray(31))))
        assertNull(decodeKeyEnvelope("!!!." + text.substringAfter('.')))
    }

    @Test
    fun `darwin picks the mac store, not windows`() {
        assertIs<MacOsKeyStore>(selectNativeOsKeyStore("mac os x"))
        assertIs<MacOsKeyStore>(selectNativeOsKeyStore("darwin"))
        assertIs<WindowsOsKeyStore>(selectNativeOsKeyStore("windows 11"))
        assertIs<LinuxOsKeyStore>(selectNativeOsKeyStore("linux"))
    }

    @Test
    fun `secret service failures separate no service from a failing one`() {
        assertIs<OsKeyRead.NoStore>(classifySecretServiceFailure("org.freedesktop.DBus.Error.ServiceUnknown: x"))
        assertIs<OsKeyRead.NoStore>(classifySecretServiceFailure("Cannot autolaunch D-Bus without X11 \$DISPLAY"))
        assertIs<OsKeyRead.Unreachable>(classifySecretServiceFailure("Client public key size is invalid"))
        assertIs<OsKeyRead.Unreachable>(classifySecretServiceFailure("Failed to unlock: dismissed"))
    }
}
