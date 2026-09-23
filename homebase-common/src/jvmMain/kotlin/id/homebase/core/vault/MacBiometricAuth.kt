package id.homebase.core.vault

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

// Device-owner policy: Touch ID with the built-in "Use Password" fallback.
private const val LA_POLICY_DEVICE_OWNER = 2L

internal suspend fun macAuthenticate(title: String, subtitle: String): BiometricResult =
    withContext(Dispatchers.IO) {
        val context = MacObjC.newLAContext()
        if (!MacObjC.canEvaluate(context)) {
            MacObjC.release(context)
            return@withContext BiometricResult.Unavailable
        }
        val reason = MacObjC.nsString(subtitle.ifBlank { title })
        suspendCancellableCoroutine<BiometricResult> { cont ->
            val block = MacReplyBlock.create(context, reason) { success ->
                if (cont.isActive) {
                    cont.resume(if (success) BiometricResult.Success else BiometricResult.Failure)
                }
            }
            cont.invokeOnCancellation { MacReplyBlock.invalidate(block) }
            MacObjC.send(context, "evaluatePolicy:localizedReason:reply:", LA_POLICY_DEVICE_OWNER, reason, block)
        }
    }

internal fun macAvailability(): Boolean {
    val context = MacObjC.newLAContext()
    return try {
        MacObjC.canEvaluate(context)
    } finally {
        MacObjC.release(context)
    }
}

private object MacObjC {
    private val objc = NativeLibrary.getInstance("objc")
    private val msgSend: Function = objc.getFunction("objc_msgSend")
    private val getClass: Function = objc.getFunction("objc_getClass")
    private val registerSel: Function = objc.getFunction("sel_registerName")

    init {
        NativeLibrary.getInstance(
            "/System/Library/Frameworks/LocalAuthentication.framework/LocalAuthentication"
        )
    }

    fun cls(name: String): Pointer = requireNotNull(getClass.invokePointer(arrayOf(name))) {
        "Objective-C class $name not found"
    }

    fun sel(name: String): Pointer = registerSel.invokePointer(arrayOf(name))

    fun send(receiver: Pointer, selector: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))

    fun sendBool(receiver: Pointer, selector: String, vararg args: Any?): Boolean =
        (msgSend.invokeInt(arrayOf(receiver, sel(selector), *args)) and 0xFF) != 0

    fun newLAContext(): Pointer {
        val allocated = requireNotNull(send(cls("LAContext"), "alloc"))
        return requireNotNull(send(allocated, "init")) { "LAContext init failed" }
    }

    fun nsString(value: String): Pointer {
        val allocated = requireNotNull(send(cls("NSString"), "alloc"))
        val bytes = value.encodeToByteArray()
        val utf8 = Memory(bytes.size + 1L).apply {
            write(0, bytes, 0, bytes.size)
            setByte(bytes.size.toLong(), 0)
        }
        return requireNotNull(send(allocated, "initWithUTF8String:", utf8))
    }

    fun release(obj: Pointer) {
        send(obj, "release")
    }

    fun canEvaluate(context: Pointer): Boolean =
        sendBool(context, "canEvaluatePolicy:error:", LA_POLICY_DEVICE_OWNER, Pointer.NULL)
}

internal interface MacReplyInvoke : Callback {
    fun invoke(block: Pointer, success: Byte, error: Pointer?)
}

// Hand-built global Block_literal: Block_copy on a global block is a no-op, so the
// native memory must stay reachable here until the reply fires.
private object MacReplyBlock {
    private const val BLOCK_IS_GLOBAL = 1 shl 28
    private const val BLOCK_HAS_SIGNATURE = 1 shl 30
    private const val LITERAL_SIZE = 32L

    private class Pending(
        val literal: Memory,
        val descriptor: Memory,
        val signature: Memory,
        val context: Pointer,
        val reason: Pointer,
        val onReply: (Boolean) -> Unit,
    ) {
        var replied = false
    }

    private val pending = ConcurrentHashMap<Long, Pending>()

    private val globalBlockIsa: Pointer =
        NativeLibrary.getInstance("System").getGlobalVariableAddress("_NSConcreteGlobalBlock")

    // Apple arm64/x86_64 ABIs extend BOOL to 32 bits at the call site, so a Byte read is safe.
    private val invoke = object : MacReplyInvoke {
        override fun invoke(block: Pointer, success: Byte, error: Pointer?) {
            val entry = pending.remove(Pointer.nativeValue(block)) ?: return
            synchronized(entry) {
                entry.replied = true
                MacObjC.release(entry.reason)
                MacObjC.release(entry.context)
            }
            entry.onReply(success.toInt() != 0)
        }
    }

    // Dismisses the sheet; LocalAuthentication then fires the reply, which does the cleanup.
    fun invalidate(block: Pointer) {
        val entry = pending[Pointer.nativeValue(block)] ?: return
        synchronized(entry) {
            if (!entry.replied) MacObjC.send(entry.context, "invalidate")
        }
    }

    fun create(context: Pointer, reason: Pointer, onReply: (Boolean) -> Unit): Pointer {
        val signature = Memory(16).apply { setString(0, "v@?B@") }
        val descriptor = Memory(24).apply {
            setLong(0, 0)
            setLong(8, LITERAL_SIZE)
            setPointer(16, signature)
        }
        val literal = Memory(LITERAL_SIZE).apply {
            setPointer(0, globalBlockIsa)
            setInt(8, BLOCK_IS_GLOBAL or BLOCK_HAS_SIGNATURE)
            setInt(12, 0)
            setPointer(16, CallbackReference.getFunctionPointer(invoke))
            setPointer(24, descriptor)
        }
        pending[Pointer.nativeValue(literal)] =
            Pending(literal, descriptor, signature, context, reason, onReply)
        return literal
    }
}
