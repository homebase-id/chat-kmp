package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class JvmFileOperationsProvider : FileOperationsProvider {
    override fun openFileInput(path: String): InputProvider {
        val inputProvider = InputProvider {
            File(path).inputStream().asInput()
        }
        return inputProvider
    }

    override suspend fun readFileBytes(path: String): ByteArray =
        withContext(Dispatchers.IO) {
            File(path).readBytes()
        }

}