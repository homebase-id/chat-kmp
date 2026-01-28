package id.homebase.api.client.drives

import io.ktor.client.request.forms.InputProvider

expect fun openFileInput(path: String): InputProvider

expect suspend fun readFileBytes(path: String): ByteArray

expect suspend fun writeBytesToTempFile(
    bytes: ByteArray,
    prefix: String,
    suffix: String
): String
