package id.homebase.api.crypto

import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * The one random source for key material in this codebase.
 *
 * Backed by cryptography-kotlin's platform-native generators — `java.security.SecureRandom`
 * on JVM/Android, `CCRandomGenerateBytes` on Apple, `crypto.getRandomValues` on wasmJs.
 *
 * Never `kotlin.random.Random`: that is a seeded xorshift PRNG, so an observer who learns
 * its state can reproduce every subsequent draw. Everything downstream of here is key
 * material — AES keys, CBC IVs and GCM nonces — where that is fatal.
 */
internal object OdinSecureRandom {
    fun nextBytes(count: Int): ByteArray {
        require(count >= 0) { "count must be non-negative, was $count" }
        return CryptographyRandom.nextBytes(count)
    }
}
