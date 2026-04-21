package id.homebase.chat.services

import id.homebase.api.common.SecureByteArray
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

interface PayloadBundleEncryptor {
    suspend fun encryptBundle(
        uniqueId: Uuid,
        bundle: PayloadBundle?,
        aesKey: SecureByteArray,
        scope: CoroutineScope
    ): PayloadBundle
}
