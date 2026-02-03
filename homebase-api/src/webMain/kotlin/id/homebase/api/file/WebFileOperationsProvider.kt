package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider

class WebFileOperationsProvider : FileOperationsProvider {
    override fun openFileInput(path: String): InputProvider {
        TODO("Not yet implemented")
    }

    override suspend fun readFileBytes(path: String): ByteArray =
        TODO("Not yet implemented")
}