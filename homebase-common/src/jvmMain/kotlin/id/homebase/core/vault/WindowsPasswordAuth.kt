package id.homebase.core.vault

import co.touchlab.kermit.Logger
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Secur32
import com.sun.jna.platform.win32.Secur32Util
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinError.ERROR_CANCELLED
import com.sun.jna.platform.win32.WinError.ERROR_INSUFFICIENT_BUFFER
import com.sun.jna.platform.win32.WinError.ERROR_LOGON_TYPE_NOT_GRANTED
import com.sun.jna.platform.win32.WinError.ERROR_SUCCESS
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

private val log = Logger.withTag("WindowsPassword")

internal data class WindowsAccount(val user: String, val domain: String?)

internal fun samAccount(samName: String): WindowsAccount {
    val slash = samName.indexOf('\\')
    return if (slash < 0) WindowsAccount(samName, null)
    else WindowsAccount(samName.substring(slash + 1), samName.substring(0, slash))
}

// Returns the account to hand LogonUser — always the current user's own name — or null for anyone else.
internal fun resolveLogonAccount(
    enteredUser: String,
    enteredDomain: String,
    currentSam: String,
    currentUpn: String?,
): WindowsAccount? {
    if (enteredDomain.isEmpty() && '@' in enteredUser) {
        return if (currentUpn != null && enteredUser.equals(currentUpn, ignoreCase = true)) {
            WindowsAccount(currentUpn, null)
        } else {
            null
        }
    }
    val entered = if (enteredDomain.isEmpty()) samAccount(enteredUser) else WindowsAccount(enteredUser, enteredDomain)
    val current = samAccount(currentSam)
    if (!entered.user.equals(current.user, ignoreCase = true)) return null
    val domainMatches = entered.domain == null || entered.domain == "." ||
        entered.domain.equals(current.domain, ignoreCase = true)
    return if (domainMatches) current else null
}

internal fun logonShouldRetryAsNetwork(lastError: Int): Boolean = lastError == ERROR_LOGON_TYPE_NOT_GRANTED

internal fun windowsPasswordAuthenticate(title: String, subtitle: String, hwnd: Pointer?): BiometricResult =
    try {
        WindowsPasswordPrompt.authenticate(title, subtitle, hwnd)
    } catch (e: UnsatisfiedLinkError) {
        log.w(e) { "Credential UI unavailable" }
        BiometricResult.Unavailable
    } catch (e: Win32Exception) {
        log.e(e) { "Password verification failed" }
        BiometricResult.Failure
    }

@Structure.FieldOrder("cbSize", "hwndParent", "pszMessageText", "pszCaptionText", "hbmBanner")
internal class CredUiInfo : Structure() {
    @JvmField var cbSize: Int = 0
    @JvmField var hwndParent: Pointer? = null
    @JvmField var pszMessageText: WString? = null
    @JvmField var pszCaptionText: WString? = null
    @JvmField var hbmBanner: Pointer? = null
}

private object WindowsPasswordPrompt {
    private const val CREDUIWIN_IN_CRED_ONLY = 0x20
    private const val CREDUIWIN_ENUMERATE_CURRENT_USER = 0x200
    private const val CRED_PACK_PROTECTED_CREDENTIALS = 0x1
    private const val LOGON32_LOGON_INTERACTIVE = 2
    private const val LOGON32_LOGON_NETWORK = 3
    private const val LOGON32_PROVIDER_DEFAULT = 0

    // CREDUI_MAX_USERNAME_LENGTH / CREDUI_MAX_DOMAIN_TARGET_LENGTH / CREDUI_MAX_PASSWORD_LENGTH, plus NUL.
    private const val MAX_USER_CHARS = 514
    private const val MAX_DOMAIN_CHARS = 338
    private const val MAX_PASSWORD_CHARS = 257

    private fun fn(dll: String, name: String) = Function.getFunction(dll, name, Function.ALT_CONVENTION)

    fun authenticate(title: String, subtitle: String, hwnd: Pointer?): BiometricResult {
        val currentSam = Secur32Util.getUserNameEx(Secur32.EXTENDED_NAME_FORMAT.NameSamCompatible)
        val currentUpn = runCatching { Secur32Util.getUserNameEx(Secur32.EXTENDED_NAME_FORMAT.NameUserPrincipal) }
            .getOrNull()

        val info = CredUiInfo().apply {
            hwndParent = hwnd
            pszCaptionText = WString(title)
            pszMessageText = WString(subtitle.ifBlank { title })
            cbSize = size()
        }
        val inBuffer = packUserName(currentSam)
        val flags = CREDUIWIN_ENUMERATE_CURRENT_USER or (if (inBuffer != null) CREDUIWIN_IN_CRED_ONLY else 0)
        val authPackage = IntByReference(0)
        val outBuffer = PointerByReference()
        val outSize = IntByReference(0)
        val code = try {
            fn("credui", "CredUIPromptForWindowsCredentialsW").invokeInt(
                arrayOf(info, 0, authPackage, inBuffer, inBuffer?.size()?.toInt() ?: 0, outBuffer, outSize, null, flags),
            )
        } finally {
            inBuffer?.clear()
        }
        if (code == ERROR_CANCELLED) return BiometricResult.Failure
        if (code != ERROR_SUCCESS) {
            log.w { "CredUIPromptForWindowsCredentialsW failed: $code" }
            return BiometricResult.Failure
        }

        val buffer = outBuffer.value ?: return BiometricResult.Failure
        val user = Memory(MAX_USER_CHARS * 2L).apply { clear() }
        val domain = Memory(MAX_DOMAIN_CHARS * 2L).apply { clear() }
        val password = Memory(MAX_PASSWORD_CHARS * 2L).apply { clear() }
        try {
            val unpacked = fn("credui", "CredUnPackAuthenticationBufferW").invokeInt(
                arrayOf(
                    CRED_PACK_PROTECTED_CREDENTIALS, buffer, outSize.value,
                    user, IntByReference(MAX_USER_CHARS),
                    domain, IntByReference(MAX_DOMAIN_CHARS),
                    password, IntByReference(MAX_PASSWORD_CHARS),
                ),
            )
            if (unpacked == 0) {
                log.w { "CredUnPackAuthenticationBufferW failed: ${Native.getLastError()}" }
                return BiometricResult.Failure
            }
            val account = resolveLogonAccount(user.getWideString(0), domain.getWideString(0), currentSam, currentUpn)
            if (account == null) {
                log.w { "Rejected credentials for a different account" }
                return BiometricResult.Failure
            }
            return if (logon(account, password)) BiometricResult.Success else BiometricResult.Failure
        } finally {
            password.clear()
            user.clear()
            domain.clear()
            buffer.setMemory(0, outSize.value.toLong(), 0)
            Ole32.INSTANCE.CoTaskMemFree(buffer)
        }
    }

    private fun packUserName(samName: String): Memory? {
        val pack = fn("credui", "CredPackAuthenticationBufferW")
        val size = IntByReference(0)
        val empty = WString("")
        if (pack.invokeInt(arrayOf(0, WString(samName), empty, null, size)) == 0 &&
            Native.getLastError() != ERROR_INSUFFICIENT_BUFFER
        ) {
            log.w { "CredPackAuthenticationBufferW size query failed: ${Native.getLastError()}" }
            return null
        }
        if (size.value <= 0) return null
        val buffer = Memory(size.value.toLong())
        if (pack.invokeInt(arrayOf(0, WString(samName), empty, buffer, size)) == 0) {
            log.w { "CredPackAuthenticationBufferW failed: ${Native.getLastError()}" }
            return null
        }
        return buffer
    }

    private fun logon(account: WindowsAccount, password: Memory): Boolean {
        val logonUser = fn("advapi32", "LogonUserW")
        fun attempt(type: Int): Pair<Boolean, Int> {
            val token = PointerByReference()
            val ok = logonUser.invokeInt(
                arrayOf(WString(account.user), account.domain?.let(::WString), password, type, LOGON32_PROVIDER_DEFAULT, token),
            ) != 0
            val error = Native.getLastError()
            if (ok) Kernel32.INSTANCE.CloseHandle(WinNT.HANDLE(token.value))
            return ok to error
        }
        val (interactive, error) = attempt(LOGON32_LOGON_INTERACTIVE)
        if (interactive) return true
        if (logonShouldRetryAsNetwork(error)) {
            val (network, networkError) = attempt(LOGON32_LOGON_NETWORK)
            if (!network) log.w { "LogonUserW (network) failed: $networkError" }
            return network
        }
        log.w { "LogonUserW failed: $error" }
        return false
    }
}
