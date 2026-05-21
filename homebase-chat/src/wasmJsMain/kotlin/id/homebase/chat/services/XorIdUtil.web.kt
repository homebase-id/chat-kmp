package id.homebase.chat.services

import id.homebase.api.crypto.HashUtil

// Delegates to the synchronous pure-Kotlin SHA-256 in homebase-api. The
// commonMain `expect fun sha256(input: ByteArray): ByteArray` is synchronous,
// but WebCrypto (the obvious wasmJs choice) is async only — so we ride on the
// same pure-Kotlin implementation OdinId uses for its sync hashing path.
actual fun sha256(input: ByteArray): ByteArray = HashUtil.sha256Sync(input)
