package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider

interface FileOperationsProvider {
    fun openFileInput(path: String): InputProvider
    suspend fun readFileBytes(path: String): ByteArray

    fun getCacheDirectory(): String
}
