package id.homebase.chat.services

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(input: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    input.usePinned { inputPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(inputPinned.addressOf(0).reinterpret<UByteVar>(), input.size.toUInt(), digestPinned.addressOf(0))
        }
    }
    return digest.toByteArray()
}
