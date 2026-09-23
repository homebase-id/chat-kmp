package id.homebase.core.vault

import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.WinError.ERROR_CANCELLED
import com.sun.jna.platform.win32.WinError.ERROR_LOGON_TYPE_NOT_GRANTED
import com.sun.jna.platform.win32.WinError.RPC_E_CHANGED_MODE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsBiometricAuthTest {

    @Test
    fun onlyAvailableHelloIsUsedEverythingElseFallsBackToPassword() {
        assertTrue(windowsHelloReady(UCV_AVAILABLE))
        (1..4).forEach { assertFalse(windowsHelloReady(it)) }
    }

    @Test
    fun onlyVerifiedSucceeds() {
        assertEquals(BiometricResult.Success, windowsVerificationResult(UCV_VERIFIED))
        (1..6).forEach { assertEquals(BiometricResult.Failure, windowsVerificationResult(it)) }
    }

    @Test
    fun hresultFormatsAsUnsignedHex() {
        assertEquals("0x80010106", hresultHex(RPC_E_CHANGED_MODE))
    }

    @Test
    fun messageSkipsBlankParts() {
        assertEquals("Vault\nConfirm it's you", windowsHelloMessage("Vault", "Confirm it's you"))
        assertEquals("Vault", windowsHelloMessage("Vault", " "))
    }

    @Test
    fun iidsParseAsGuids() {
        listOf(
            IID_USER_CONSENT_VERIFIER_STATICS,
            IID_USER_CONSENT_VERIFIER_INTEROP,
            IID_ASYNC_OPERATION_VERIFICATION_RESULT,
            IID_ASYNC_INFO,
        ).forEach { assertEquals(it, Guid.GUID(it).toGuidString()) }
    }

    @Test
    fun samNameSplitsIntoDomainAndUser() {
        assertEquals(WindowsAccount("alice", "DESKTOP-1"), samAccount("DESKTOP-1\\alice"))
        assertEquals(WindowsAccount("alice", null), samAccount("alice"))
    }

    @Test
    fun currentUserInAnyFormResolvesToTheSamAccount() {
        val current = WindowsAccount("alice", "DESKTOP-1")
        assertEquals(current, resolveLogonAccount("DESKTOP-1\\alice", "", "DESKTOP-1\\alice", null))
        assertEquals(current, resolveLogonAccount("ALICE", "", "DESKTOP-1\\alice", null))
        assertEquals(current, resolveLogonAccount("alice", ".", "DESKTOP-1\\alice", null))
        assertEquals(current, resolveLogonAccount("alice", "desktop-1", "DESKTOP-1\\alice", null))
    }

    @Test
    fun anotherAccountIsRejected() {
        assertNull(resolveLogonAccount("bob", "", "DESKTOP-1\\alice", null))
        assertNull(resolveLogonAccount("OTHER\\alice", "", "DESKTOP-1\\alice", null))
        assertNull(resolveLogonAccount("alice", "CORP", "DESKTOP-1\\alice", null))
        assertNull(resolveLogonAccount("admin@corp.com", "", "CORP\\alice", "alice@corp.com"))
        assertNull(resolveLogonAccount("alice@corp.com", "", "CORP\\alice", null))
    }

    @Test
    fun upnMatchesOnlyTheCurrentUpn() {
        assertEquals(
            WindowsAccount("alice@corp.com", null),
            resolveLogonAccount("Alice@Corp.com", "", "CORP\\alice", "alice@corp.com"),
        )
    }

    @Test
    fun onlyLogonTypeNotGrantedRetriesAsNetwork() {
        assertTrue(logonShouldRetryAsNetwork(ERROR_LOGON_TYPE_NOT_GRANTED))
        assertFalse(logonShouldRetryAsNetwork(1326))
        assertFalse(logonShouldRetryAsNetwork(ERROR_CANCELLED))
    }
}
