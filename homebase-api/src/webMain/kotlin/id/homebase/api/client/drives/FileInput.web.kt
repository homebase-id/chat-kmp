package id.homebase.api.client.drives

import io.ktor.client.request.forms.InputProvider

actual fun openFileInput(path: String): InputProvider {
    TODO("Not yet implemented")
}

actual suspend fun readFileBytes(path: String): ByteArray {
    TODO("Not yet implemented")
}

actual suspend fun writeBytesToTempFile(
    bytes: ByteArray,
    prefix: String,
    suffix: String
): String {
    TODO("Not yet implemented")
}