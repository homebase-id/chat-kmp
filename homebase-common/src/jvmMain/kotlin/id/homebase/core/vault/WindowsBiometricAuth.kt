package id.homebase.core.vault

import co.touchlab.kermit.Logger
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.COM.COMUtils.FAILED
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinError.E_ABORT
import com.sun.jna.platform.win32.WinError.RPC_E_CHANGED_MODE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.KeyboardFocusManager
import java.util.concurrent.Executors

private val log = Logger.withTag("WindowsHello")

internal suspend fun windowsAuthenticate(title: String, subtitle: String): BiometricResult {
    val hwnd = activeWindowHandle()
    // COM apartment state is per thread, so every WinRT call runs on one thread we own.
    val executor = Executors.newSingleThreadExecutor { Thread(it, "windows-hello").apply { isDaemon = true } }
    val dispatcher = executor.asCoroutineDispatcher()
    return try {
        withContext(dispatcher) {
            WindowsHello.authenticate(windowsHelloMessage(title, subtitle), hwnd)
                ?: windowsPasswordAuthenticate(title, subtitle, hwnd)
        }
    } finally {
        dispatcher.close()
    }
}

internal fun windowsHelloMessage(title: String, subtitle: String): String =
    listOf(title, subtitle).filter { it.isNotBlank() }.joinToString("\n")

// Anything but Available (incl. DisabledByPolicy and DeviceBusy) falls back to the account password.
internal fun windowsHelloReady(availability: Int): Boolean = availability == UCV_AVAILABLE

internal fun windowsVerificationResult(result: Int): BiometricResult =
    if (result == UCV_VERIFIED) BiometricResult.Success else BiometricResult.Failure

internal fun hresultHex(hr: Int): String = "0x%08X".format(hr)

internal class WinRtException(val hr: Int, what: String) : Exception("$what failed: ${hresultHex(hr)}")

internal const val UCV_AVAILABLE = 0
internal const val UCV_VERIFIED = 0

internal const val ASYNC_STARTED = 0
internal const val ASYNC_COMPLETED = 1
internal const val ASYNC_CANCELED = 2

internal const val RUNTIME_CLASS_USER_CONSENT_VERIFIER = "Windows.Security.Credentials.UI.UserConsentVerifier"
internal const val IID_USER_CONSENT_VERIFIER_STATICS = "{AF4F3F91-564C-4DDC-B8B5-973447627C65}"
internal const val IID_USER_CONSENT_VERIFIER_INTEROP = "{39E050C3-4E74-441A-8DC0-B81104DF949C}"
internal const val IID_ASYNC_OPERATION_VERIFICATION_RESULT = "{FD596FFD-2318-558F-9DBE-D21DF43764A5}"
internal const val IID_ASYNC_INFO = "{00000036-0000-0000-C000-000000000046}"

private fun activeWindowHandle(): Pointer? {
    val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    val awt = window?.let { runCatching { Native.getWindowPointer(it) }.getOrNull() }
    return awt ?: User32.INSTANCE.GetForegroundWindow()?.pointer
}

private object WindowsHello {
    private const val RO_INIT_MULTITHREADED = 1
    private const val POLL_MS = 50L

    // IInspectable occupies slots 0..5.
    private const val SLOT_QUERY_INTERFACE = 0
    private const val SLOT_RELEASE = 2
    private const val SLOT_STATICS_CHECK_AVAILABILITY = 6
    private const val SLOT_STATICS_REQUEST_VERIFICATION = 7
    private const val SLOT_INTEROP_REQUEST_FOR_WINDOW = 6
    private const val SLOT_OPERATION_GET_RESULTS = 8
    private const val SLOT_INFO_STATUS = 7
    private const val SLOT_INFO_ERROR_CODE = 8
    private const val SLOT_INFO_CANCEL = 9
    private const val SLOT_INFO_CLOSE = 10

    private fun combase(name: String) = Function.getFunction("combase", name, Function.ALT_CONVENTION)

    // null = Hello can't run here; the caller falls back to the password prompt.
    suspend fun authenticate(message: String, hwnd: Pointer?): BiometricResult? {
        val init = try {
            combase("RoInitialize").invokeInt(arrayOf(RO_INIT_MULTITHREADED))
        } catch (e: UnsatisfiedLinkError) {
            log.w(e) { "combase unavailable" }
            return null
        }
        if (FAILED(init) && init != RPC_E_CHANGED_MODE) {
            log.w { "RoInitialize failed: ${hresultHex(init)}" }
            return null
        }
        try {
            val statics = activationFactory(IID_USER_CONSENT_VERIFIER_STATICS) ?: return null
            try {
                val availability = try {
                    await(call(statics, SLOT_STATICS_CHECK_AVAILABILITY, "CheckAvailabilityAsync"))
                } catch (e: WinRtException) {
                    log.w(e) { "CheckAvailabilityAsync failed" }
                    return null
                }
                if (!windowsHelloReady(availability)) {
                    log.i { "Windows Hello availability=$availability, using password" }
                    return null
                }
                val verification = withHString(message) { hMessage ->
                    await(requestVerification(statics, hMessage, hwnd))
                }
                log.i { "Windows Hello verification=$verification" }
                return windowsVerificationResult(verification)
            } finally {
                release(statics)
            }
        } catch (e: WinRtException) {
            log.e(e) { "Windows Hello failed" }
            return BiometricResult.Failure
        } finally {
            if (!FAILED(init)) combase("RoUninitialize").invokeVoid(emptyArray())
        }
    }

    private fun requestVerification(statics: Pointer, message: Pointer?, hwnd: Pointer?): Pointer {
        val interop = if (hwnd != null) activationFactory(IID_USER_CONSENT_VERIFIER_INTEROP) else null
        if (interop == null) {
            log.w { "Falling back to unparented RequestVerificationAsync (hwnd=$hwnd)" }
            return call(statics, SLOT_STATICS_REQUEST_VERIFICATION, "RequestVerificationAsync", message)
        }
        try {
            val riid = guidRef(IID_ASYNC_OPERATION_VERIFICATION_RESULT)
            return call(interop, SLOT_INTEROP_REQUEST_FOR_WINDOW, "RequestVerificationForWindowAsync", hwnd, message, riid)
        } finally {
            release(interop)
        }
    }

    private suspend fun await(operation: Pointer): Int {
        try {
            val info = queryInterface(operation, IID_ASYNC_INFO)
            try {
                while (true) {
                    val status = IntByReference()
                    requireOk(vtable(info, SLOT_INFO_STATUS).invokeInt(arrayOf(info, status)), "IAsyncInfo.Status")
                    when (status.value) {
                        ASYNC_STARTED -> try {
                            delay(POLL_MS)
                        } catch (e: CancellationException) {
                            vtable(info, SLOT_INFO_CANCEL).invokeInt(arrayOf(info))
                            throw e
                        }
                        ASYNC_COMPLETED -> {
                            val result = IntByReference()
                            requireOk(
                                vtable(operation, SLOT_OPERATION_GET_RESULTS).invokeInt(arrayOf(operation, result)),
                                "IAsyncOperation.GetResults",
                            )
                            return result.value
                        }
                        ASYNC_CANCELED -> throw WinRtException(E_ABORT, "async operation canceled")
                        else -> {
                            val error = IntByReference()
                            vtable(info, SLOT_INFO_ERROR_CODE).invokeInt(arrayOf(info, error))
                            throw WinRtException(error.value, "async operation")
                        }
                    }
                }
            } finally {
                vtable(info, SLOT_INFO_CLOSE).invokeInt(arrayOf(info))
                release(info)
            }
        } finally {
            release(operation)
        }
    }

    private fun activationFactory(iid: String): Pointer? = withHString(RUNTIME_CLASS_USER_CONSENT_VERIFIER) { cls ->
        val out = PointerByReference()
        val hr = combase("RoGetActivationFactory").invokeInt(arrayOf(cls, guidRef(iid), out))
        if (FAILED(hr)) {
            log.w { "RoGetActivationFactory($iid) failed: ${hresultHex(hr)}" }
            null
        } else {
            out.value
        }
    }

    private fun queryInterface(obj: Pointer, iid: String): Pointer {
        val out = PointerByReference()
        requireOk(vtable(obj, SLOT_QUERY_INTERFACE).invokeInt(arrayOf(obj, guidRef(iid), out)), "QueryInterface($iid)")
        return out.value
    }

    private fun call(obj: Pointer, slot: Int, what: String, vararg args: Any?): Pointer {
        val out = PointerByReference()
        requireOk(vtable(obj, slot).invokeInt(arrayOf(obj, *args, out)), what)
        return requireNotNull(out.value) { "$what returned null" }
    }

    private fun release(obj: Pointer) {
        vtable(obj, SLOT_RELEASE).invokeInt(arrayOf(obj))
    }

    private fun vtable(obj: Pointer, slot: Int): Function =
        Function.getFunction(obj.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)

    private fun requireOk(hr: Int, what: String) {
        if (FAILED(hr)) throw WinRtException(hr, what)
    }

    private fun guidRef(iid: String): Guid.GUID.ByReference = Guid.GUID.ByReference(Guid.GUID(iid))

    private inline fun <T> withHString(value: String, block: (Pointer?) -> T): T {
        val out = PointerByReference()
        requireOk(combase("WindowsCreateString").invokeInt(arrayOf(WString(value), value.length, out)), "WindowsCreateString")
        try {
            return block(out.value)
        } finally {
            combase("WindowsDeleteString").invokeInt(arrayOf(out.value))
        }
    }
}
